package com.windmill.service.notification;

import com.windmill.domain.AlertEvent;
import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.domain.PushSubscription;
import com.windmill.dto.TriggerLevel;
import com.windmill.dto.TriggerResult;
import com.windmill.repository.AlertEventRepository;
import com.windmill.repository.ItineraryRepository;
import com.windmill.repository.PushSubscriptionRepository;
import com.windmill.service.push.PushSenderService;
import com.windmill.service.trigger.TriggerDetectionService;
import com.windmill.util.ClosingTimeGate;
import com.windmill.util.KoreaClock;
import com.windmill.util.VisitTiming;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 여행 당일 알림:
 * <ol>
 *   <li>순풍(NORMAL) - 오늘 일정 중 가장 이른 시작 시각 30분 전, 마지막 일정 점유 종료 직후.</li>
 *   <li>주황·빨강(WARNING/DANGER) - 비·폭염·혼잡·동선 등 변경이 필요한 순간 즉시.
 *       같은 원인이 유지되면 재발송하지 않고, 새 원인이 생기거나 단계가 나빠지면 다시 보낸다.</li>
 * </ol>
 * 주황·빨강은 북엔드와 절대 병합하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationSchedulerService {

    static final int START_LEAD_MINUTES = 30;
    /** 마지막 일정 종료 후 이 안에 들어오면 마무리 알림. 넘으면 발송 없이 마킹만(캐치업 스팸 방지). */
    static final int DAY_END_GRACE_MINUTES = 120;

    private final ItineraryRepository itineraryRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final AlertEventRepository alertEventRepository;
    private final TriggerDetectionService triggerDetectionService;
    private final NotificationComposer composer;
    private final PushSenderService pushSenderService;

    @Scheduled(initialDelay = 1, fixedDelay = 2, timeUnit = TimeUnit.MINUTES)
    void tick() {
        runTick(KoreaClock.now());
    }

    /** 실제 처리 본문 - KoreaClock을 목킹하지 않고 명시적 now로 테스트하기 위해 분리. */
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
        List<PushSubscription> subs = resolveSubscriptions(itinerary);
        if (subs.isEmpty()) {
            return;
        }

        TriggerResult result = triggerDetectionService.detectForItinerary(itinerary)
                .blockOptional(Duration.ofSeconds(20)).orElse(null);
        if (result == null) {
            return;
        }

        handleUrgentStatus(itinerary, subs, result, now);
        handleDayBookends(itinerary, subs, result, now);

        itineraryRepository.save(itinerary);
    }

    /**
     * 일정에 묶인 구독 + 같은 세션의 공통 구독(설정에서만 켠 경우 itineraryId=null)을 토큰 기준으로 합친다.
     */
    List<PushSubscription> resolveSubscriptions(Itinerary itinerary) {
        Map<String, PushSubscription> byToken = new LinkedHashMap<>();
        if (itinerary.getId() != null) {
            for (PushSubscription sub : pushSubscriptionRepository.findByItineraryId(itinerary.getId())) {
                putSubscription(byToken, sub);
            }
        }
        if (itinerary.getSessionUuid() != null && !itinerary.getSessionUuid().isBlank()) {
            for (PushSubscription sub : pushSubscriptionRepository.findBySessionUuid(itinerary.getSessionUuid())) {
                putSubscriptionIfAbsent(byToken, sub);
            }
        }
        return List.copyOf(byToken.values());
    }

    private static void putSubscription(Map<String, PushSubscription> byToken, PushSubscription sub) {
        if (sub == null || sub.getFcmToken() == null || sub.getFcmToken().isBlank()) {
            return;
        }
        byToken.put(sub.getFcmToken(), sub);
    }

    private static void putSubscriptionIfAbsent(Map<String, PushSubscription> byToken, PushSubscription sub) {
        if (sub == null || sub.getFcmToken() == null || sub.getFcmToken().isBlank()) {
            return;
        }
        byToken.putIfAbsent(sub.getFcmToken(), sub);
    }

    /**
     * 주황·빨강은 처음 감지되는 즉시 보낸다(구독 직후 첫 틱이어도). 같은 원인 유지 중이면 침묵하고,
     * 비·폭염·혼잡·동선 등 새 깃발이 생기거나 단계가 나빠지면 다시 보낸다.
     */
    private void handleUrgentStatus(Itinerary itinerary, List<PushSubscription> subs,
                                    TriggerResult result, LocalDateTime now) {
        TriggerLevel oldLevel = itinerary.getLastKnownTriggerLevel();
        TriggerLevel newLevel = result.getLevel();
        String oldSig = itinerary.getLastKnownTriggerSignature() == null
                ? "" : itinerary.getLastKnownTriggerSignature();
        String newSig = triggerSignature(result);
        if (newSig.isEmpty() && isUrgent(newLevel)) {
            newSig = "LEVEL:" + newLevel;
        }

        itinerary.setLastKnownTriggerLevel(newLevel);
        itinerary.setLastKnownTriggerSignature(newSig);

        if (!isUrgent(newLevel)) {
            return;
        }

        boolean fromNormalOrUnknown = oldLevel == null || oldLevel == TriggerLevel.NORMAL;
        boolean worsened = oldLevel != null && newLevel.ordinal() > oldLevel.ordinal();
        boolean newProblem = !addedFlags(oldSig, newSig).isEmpty();
        if (!fromNormalOrUnknown && !worsened && !newProblem) {
            return;
        }

        String nudgeId = "STATUS:" + newSig + "@" + minuteKey(now);
        String title = composer.statusTitle(newLevel);
        String body = composer.statusBody(result);
        recordAlertEvent(itinerary, "STATUS", result, title, body);
        dispatch(itinerary, subs, title, body, nudgeId, now, false);
    }

    private void handleDayBookends(Itinerary itinerary, List<PushSubscription> subs,
                                   TriggerResult result, LocalDateTime now) {
        TripBounds bounds = resolveBounds(itinerary, now.toLocalDate());
        if (bounds == null) {
            return;
        }
        LocalTime nowTime = now.toLocalTime();

        if (!itinerary.isDayStartNotified()) {
            LocalTime dueAt = bounds.firstStart().minusMinutes(START_LEAD_MINUTES);
            if (dueAt.isAfter(bounds.firstStart())) {
                dueAt = LocalTime.of(0, 0); // 자정 넘김 clamp (당일치기)
            }
            boolean inLeadWindow = !nowTime.isBefore(dueAt) && nowTime.isBefore(bounds.firstStart());
            if (inLeadWindow && result.getLevel() == TriggerLevel.NORMAL) {
                String title = composer.dayStartTitle();
                String body = composer.dayStartBody();
                recordBookend(itinerary, "DAY_START", title, body);
                dispatch(itinerary, subs, title, body, "DAY_START@" + minuteKey(now), now, false);
                itinerary.setDayStartNotified(true);
            } else if (!nowTime.isBefore(bounds.firstStart())) {
                itinerary.setDayStartNotified(true); // 창을 놓침 - 발송 없이 마킹
            }
        }

        if (!itinerary.isDayEndNotified()) {
            long minutesSinceEnd = Duration.between(bounds.lastEnd(), nowTime).toMinutes();
            if (minutesSinceEnd > DAY_END_GRACE_MINUTES) {
                itinerary.setDayEndNotified(true);
            } else if (minutesSinceEnd >= 0 && result.getLevel() == TriggerLevel.NORMAL) {
                String title = composer.dayEndTitle();
                String body = composer.dayEndBody();
                recordBookend(itinerary, "DAY_END", title, body);
                dispatch(itinerary, subs, title, body, "DAY_END@" + minuteKey(now), now, true);
                itinerary.setDayEndNotified(true);
            }
        }
    }

    /** 오늘 항목 중 가장 이른 시작 ~ 가장 늦은 점유 종료. 시각이 하나도 없으면 null. */
    TripBounds resolveBounds(Itinerary itinerary, LocalDate today) {
        LocalTime firstStart = null;
        LocalTime lastEnd = null;
        for (ItineraryItem item : itinerary.getItems()) {
            LocalDate visit = item.getVisitDate() != null ? item.getVisitDate() : itinerary.getStartDate();
            if (visit != null && !visit.equals(today)) {
                continue;
            }
            LocalTime start = ClosingTimeGate.parseHhMm(item.getScheduledTime());
            if (start == null) {
                continue;
            }
            if (firstStart == null || start.isBefore(firstStart)) {
                firstStart = start;
            }
            LocalTime end = VisitTiming.occupancyEnd(item);
            if (end == null) {
                end = start;
            }
            if (lastEnd == null || end.isAfter(lastEnd)) {
                lastEnd = end;
            }
        }
        if (firstStart == null || lastEnd == null) {
            return null;
        }
        return new TripBounds(firstStart, lastEnd);
    }

    static String triggerSignature(TriggerResult result) {
        if (result == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (result.isWeatherTrigger()) {
            parts.add("RAIN");
        }
        if (result.isHeatUrgent()) {
            parts.add("HEAT_URGENT");
        } else if (result.isHeatTrigger()) {
            parts.add("HEAT");
        }
        if (result.isCrowdUrgent()) {
            parts.add("CROWD_URGENT");
        } else if (result.isCrowdTrigger()) {
            parts.add("CROWD");
        }
        if (result.isRouteTangleTrigger()) {
            parts.add("ROUTE");
        }
        if (result.isTravelTimeTrigger()) {
            parts.add("TRAVEL");
        }
        if (result.isClosedDayTrigger()) {
            parts.add("CLOSED");
        }
        if (result.isHoursEndedTrigger()) {
            parts.add("HOURS");
        }
        return String.join(",", parts);
    }

    private static Set<String> addedFlags(String oldSig, String newSig) {
        Set<String> added = parseFlags(newSig);
        added.removeAll(parseFlags(oldSig));
        return added;
    }

    private static Set<String> parseFlags(String signature) {
        Set<String> flags = new LinkedHashSet<>();
        if (signature == null || signature.isBlank()) {
            return flags;
        }
        for (String part : signature.split(",")) {
            if (!part.isBlank()) {
                flags.add(part.trim());
            }
        }
        return flags;
    }

    private static boolean isUrgent(TriggerLevel level) {
        return level == TriggerLevel.WARNING || level == TriggerLevel.DANGER;
    }

    private void dispatch(Itinerary itinerary, List<PushSubscription> subs, String title, String body,
                          String nudgeId, LocalDateTime now, boolean finish) {
        String todayKey = now.toLocalDate() + ":" + nudgeId;
        String url = "/?open=" + itinerary.getId() + (finish ? "&finish=1" : "");
        Map<String, String> data = Map.of(
                "itineraryId", String.valueOf(itinerary.getId()),
                "url", url);
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

    private void recordAlertEvent(Itinerary itinerary, String kind, TriggerResult result, String title, String body) {
        alertEventRepository.save(AlertEvent.builder()
                .itineraryId(itinerary.getId())
                .kind(kind)
                .level(result.getLevel())
                .icon(AlertIconResolver.resolve(result))
                .headline(title)
                .detail(body)
                .build());
    }

    private void recordBookend(Itinerary itinerary, String kind, String title, String body) {
        alertEventRepository.save(AlertEvent.builder()
                .itineraryId(itinerary.getId())
                .kind(kind)
                .level(TriggerLevel.NORMAL)
                .icon("🟢")
                .headline(title)
                .detail(body)
                .build());
    }

    private static String minuteKey(LocalDateTime now) {
        return now.truncatedTo(ChronoUnit.MINUTES).toString();
    }

    record TripBounds(LocalTime firstStart, LocalTime lastEnd) {
    }
}
