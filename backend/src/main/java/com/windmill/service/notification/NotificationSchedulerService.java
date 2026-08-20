package com.windmill.service.notification;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.domain.PushSubscription;
import com.windmill.dto.TriggerLevel;
import com.windmill.dto.TriggerResult;
import com.windmill.repository.ItineraryRepository;
import com.windmill.repository.PushSubscriptionRepository;
import com.windmill.service.push.PushSenderService;
import com.windmill.service.trigger.TriggerDetectionService;
import com.windmill.util.KoreaClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 여행 당일 알림 3종을 한 틱에서 함께 처리한다 - 정기 상태 알림(30분 주기, 순풍이어도 발송),
 * 슬롯 할일 알림(scheduledTime 도달), 상태 악화 즉시 알림(레벨이 나빠지는 순간, 항상 독립 발송).
 * 정기+슬롯은 같은 틱에 겹치면 하나로 병합하고, 상태 악화 알림은 절대 병합하지 않는다(최우선순위 -
 * 다른 알림과 타이밍이 겹쳐도 별도로 나간다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationSchedulerService {

    private static final int PERIODIC_INTERVAL_MINUTES = 30;
    /** 슬롯 "방금 도달" 판정 유예창 - 이 안에 들어오면 발송 대상, 넘으면 발송 없이 마킹만(캐치업 스팸 방지) */
    private static final int SLOT_DUE_GRACE_MINUTES = 10;
    private static final int WINDOW_START_BUFFER_MINUTES = 30;
    private static final int WINDOW_END_BUFFER_MINUTES = 120;
    /** 일정에 파싱 가능한 scheduledTime이 하나도 없을 때의 폴백 활성 시간창 */
    private static final LocalTime FALLBACK_WINDOW_START = LocalTime.of(8, 30);
    private static final LocalTime FALLBACK_WINDOW_END = LocalTime.of(20, 0);

    private final ItineraryRepository itineraryRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final TriggerDetectionService triggerDetectionService;
    private final NotificationComposer composer;
    private final PushSenderService pushSenderService;

    @Scheduled(initialDelay = 1, fixedDelay = 2, timeUnit = TimeUnit.MINUTES)
    void tick() {
        runTick(KoreaClock.now());
    }

    /** 실제 처리 본문 - KoreaClock을 목킹하지 않고 명시적 now로 테스트하기 위해 분리
     *  (BusinessHoursEvaluatorTest가 명시적 날짜를 넘기는 방식과 동일). */
    void runTick(LocalDateTime now) {
        List<Itinerary> active = itineraryRepository.findActiveTodayForNotification(now.toLocalDate());
        for (Itinerary itinerary : active) {
            try {
                processItinerary(itinerary, now);
            } catch (Exception e) {
                log.warn("[Notification] itinerary={} 처리 실패: {}", itinerary.getId(), e.toString());
            }
        }
    }

    private void processItinerary(Itinerary itinerary, LocalDateTime now) {
        List<PushSubscription> subs = pushSubscriptionRepository.findByItineraryId(itinerary.getId());
        if (subs.isEmpty()) {
            return; // 구독자 없으면 트리거 계산 자체를 생략 - 비용 절감
        }

        TriggerResult result = triggerDetectionService.detectForItinerary(itinerary)
                .blockOptional(Duration.ofSeconds(20)).orElse(null);
        if (result == null) {
            return;
        }

        handleStatusDegradation(itinerary, subs, result, now); // Type3 - 독립, 항상
        handlePeriodicAndSlots(itinerary, subs, result, now);  // Type1 + Type2 - 필요 시 병합

        itineraryRepository.save(itinerary); // items는 cascade ALL이라 slotNotified도 함께 저장
    }

    private void handleStatusDegradation(Itinerary itinerary, List<PushSubscription> subs,
                                          TriggerResult result, LocalDateTime now) {
        TriggerLevel oldLevel = itinerary.getLastKnownTriggerLevel();
        TriggerLevel newLevel = result.getLevel();
        itinerary.setLastKnownTriggerLevel(newLevel); // 발송 여부와 무관하게 항상 갱신

        // oldLevel == null(최초 관측)이면 기준선만 세우고 알림은 보내지 않는다 - 구독 직후 첫 틱에
        // 이미 WARNING/DANGER인 상태를 "방금 악화됐다"고 오판하지 않기 위함.
        boolean worsened = oldLevel != null && newLevel != null && newLevel.ordinal() > oldLevel.ordinal();
        if (!worsened) {
            return;
        }

        String nudgeId = "STATUS:" + oldLevel + "->" + newLevel + "@" + minuteKey(now);
        dispatch(itinerary, subs, composer.statusTitle(newLevel), composer.statusBody(result), nudgeId, now);
    }

    private void handlePeriodicAndSlots(Itinerary itinerary, List<PushSubscription> subs,
                                         TriggerResult result, LocalDateTime now) {
        markStaleSlotsWithoutSending(itinerary, now);

        TripWindow window = resolveWindow(itinerary);
        if (!window.contains(now.toLocalTime())) {
            return;
        }

        boolean periodicDue = itinerary.getLastPeriodicNotifiedAt() == null
                || Duration.between(itinerary.getLastPeriodicNotifiedAt(), now).toMinutes() >= PERIODIC_INTERVAL_MINUTES;
        List<ItineraryItem> justReached = findJustReachedSlots(itinerary, now);

        if (!periodicDue && justReached.isEmpty()) {
            return;
        }

        String nudgeId;
        String title;
        String body;
        if (periodicDue && !justReached.isEmpty()) {
            nudgeId = "PERIODIC+SLOT:" + idsOf(justReached) + "@" + minuteKey(now);
            title = composer.mergedTitle();
            body = composer.mergedBody(result, justReached);
        } else if (periodicDue) {
            nudgeId = "PERIODIC@" + minuteKey(now);
            title = composer.periodicTitle();
            body = composer.periodicBody(result);
        } else {
            nudgeId = "SLOT:" + idsOf(justReached) + "@" + minuteKey(now);
            title = composer.slotTitle(justReached);
            body = composer.slotBody(justReached);
        }

        dispatch(itinerary, subs, title, body, nudgeId, now);

        if (periodicDue) {
            itinerary.setLastPeriodicNotifiedAt(now);
        }
        justReached.forEach(item -> item.setSlotNotified(true));
    }

    /** [now-유예창, now] 구간에 막 들어온 미통보 슬롯들 - 여러 항목이 같은 시각에 몰려도 한 번에 병합해 보낸다 */
    private List<ItineraryItem> findJustReachedSlots(Itinerary itinerary, LocalDateTime now) {
        LocalTime nowTime = now.toLocalTime();
        List<ItineraryItem> result = new ArrayList<>();
        for (ItineraryItem item : itinerary.getItems()) {
            if (item.isSlotNotified()) {
                continue;
            }
            LocalTime slotTime = parseTime(item.getScheduledTime());
            if (slotTime == null) {
                continue;
            }
            long minutesSince = Duration.between(slotTime, nowTime).toMinutes();
            if (minutesSince >= 0 && minutesSince <= SLOT_DUE_GRACE_MINUTES) {
                result.add(item);
            }
        }
        return result;
    }

    /** 유예창보다 더 과거에 이미 지났고 아직 미통보인 슬롯 - 발송 없이 notified만 마킹(배포 재시작 등으로
     *  인한 캐치업 스팸 방지). 미래 슬롯은 그대로 둔다(아직 도달 전). */
    private void markStaleSlotsWithoutSending(Itinerary itinerary, LocalDateTime now) {
        LocalTime nowTime = now.toLocalTime();
        for (ItineraryItem item : itinerary.getItems()) {
            if (item.isSlotNotified()) {
                continue;
            }
            LocalTime slotTime = parseTime(item.getScheduledTime());
            if (slotTime == null) {
                continue;
            }
            long minutesSince = Duration.between(slotTime, nowTime).toMinutes();
            if (minutesSince > SLOT_DUE_GRACE_MINUTES) {
                item.setSlotNotified(true);
            }
        }
    }

    /** 일정 항목들의 scheduledTime min/max ± 버퍼. 파싱 가능한 시각이 하나도 없으면 폴백 시간창 */
    private TripWindow resolveWindow(Itinerary itinerary) {
        List<LocalTime> times = itinerary.getItems().stream()
                .map(item -> parseTime(item.getScheduledTime()))
                .filter(Objects::nonNull)
                .toList();
        if (times.isEmpty()) {
            return new TripWindow(FALLBACK_WINDOW_START, FALLBACK_WINDOW_END);
        }
        LocalTime min = Collections.min(times);
        LocalTime max = Collections.max(times);
        return new TripWindow(subtractBuffer(min, WINDOW_START_BUFFER_MINUTES), addBuffer(max, WINDOW_END_BUFFER_MINUTES));
    }

    private void dispatch(Itinerary itinerary, List<PushSubscription> subs, String title, String body,
                           String nudgeId, LocalDateTime now) {
        String todayKey = now.toLocalDate() + ":" + nudgeId;
        Map<String, String> data = Map.of(
                "itineraryId", String.valueOf(itinerary.getId()),
                "url", "/?open=" + itinerary.getId());
        for (PushSubscription sub : subs) {
            if (todayKey.equals(sub.getLastSentKey())) {
                log.info("[Notification] 중복 스킵 itinerary={} nudgeId={}", itinerary.getId(), nudgeId);
                continue;
            }
            boolean sent = pushSenderService.send(sub.getFcmToken(), title, body, data);
            log.info("[Notification] itinerary={} nudgeId={} sent={}", itinerary.getId(), nudgeId, sent);
            sub.setLastSentKey(todayKey);
            pushSubscriptionRepository.save(sub);
        }
    }

    private static LocalTime parseTime(String scheduledTime) {
        if (scheduledTime == null || scheduledTime.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(scheduledTime);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 자정을 넘어가는 예외 케이스는 당일 끝/시작으로 clamp한다(당일치기라 다음날로 안 넘김) */
    private static LocalTime addBuffer(LocalTime t, int minutes) {
        LocalTime result = t.plusMinutes(minutes);
        return result.isBefore(t) ? LocalTime.of(23, 59) : result;
    }

    private static LocalTime subtractBuffer(LocalTime t, int minutes) {
        LocalTime result = t.minusMinutes(minutes);
        return result.isAfter(t) ? LocalTime.of(0, 0) : result;
    }

    private static String idsOf(List<ItineraryItem> items) {
        return items.stream().map(ItineraryItem::getId).filter(Objects::nonNull)
                .sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static String minuteKey(LocalDateTime now) {
        return now.truncatedTo(ChronoUnit.MINUTES).toString();
    }

    private record TripWindow(LocalTime start, LocalTime end) {
        boolean contains(LocalTime t) {
            return !t.isBefore(start) && !t.isAfter(end);
        }
    }
}
