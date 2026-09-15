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
import com.windmill.service.itinerary.ProposalService;
import com.windmill.service.push.PushSenderService;
import com.windmill.service.trigger.TriggerDetectionService;
import com.windmill.util.ClosingTimeGate;
import com.windmill.util.KoreaClock;
import com.windmill.util.VisitTiming;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
    /** 한 틱에서 동시에 처리할 일정 수 상한 - 하나가 느려도(외부 API 최대 20초) 나머지가 순서대로
     *  막히지 않게 한다(2026-09-15 코드 리뷰에서 발견 - 이전엔 for 루프로 완전 순차 처리했다). */
    static final int TICK_CONCURRENCY = 5;

    private final ItineraryRepository itineraryRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;
    private final AlertEventRepository alertEventRepository;
    private final TriggerDetectionService triggerDetectionService;
    private final NotificationComposer composer;
    private final PushSenderService pushSenderService;
    private final ProposalService proposalService;

    @Scheduled(initialDelay = 1, fixedDelay = 2, timeUnit = TimeUnit.MINUTES)
    void tick() {
        runTick(KoreaClock.now());
    }

    /**
     * 실제 처리 본문 - KoreaClock을 목킹하지 않고 명시적 now로 테스트하기 위해 분리.
     *
     * <p>일정별 처리를 {@link Schedulers#boundedElastic()}에서 최대 {@value #TICK_CONCURRENCY}건
     * 동시에 돌린다. 예전엔 for 루프로 완전 순차 처리해서, detectForItinerary 하나가 외부 API
     * 지연으로 20초 가까이 걸리면 뒤 순서 일정들이 그만큼 밀렸다(2026-09-15 코드 리뷰에서 발견 -
     * 구독 없는 일정도 매 틱 감지를 도는 것 자체는 의도한 변경이라 되돌리지 않고, 대신 서로 막지
     * 않게 병렬화했다).
     */
    void runTick(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        List<Itinerary> active = itineraryRepository.findActiveTodayForNotification(today);
        Flux.fromIterable(active)
                .filter(itinerary -> itinerary.getStartDate() == null || itinerary.getStartDate().equals(today))
                .flatMap(itinerary -> Mono.fromRunnable(() -> {
                            try {
                                processItinerary(itinerary, now);
                            } catch (Exception e) {
                                log.warn("[Notification] itinerary={} 처리 실패: {}", itinerary.getId(), e.toString());
                            }
                        }).subscribeOn(Schedulers.boundedElastic()),
                        TICK_CONCURRENCY)
                .blockLast(Duration.ofMinutes(1));
    }

    private void processItinerary(Itinerary itinerary, LocalDateTime now) {
        TriggerResult result = triggerDetectionService.detectForItinerary(itinerary)
                .blockOptional(Duration.ofSeconds(20)).orElse(null);
        if (result == null) {
            return;
        }

        // 푸시 구독이 없어도(권한 미허용 등) 상태 감지·제안 생성은 그대로 돈다 - 알림 발송만 구독에 의존한다.
        List<PushSubscription> subs = resolveSubscriptions(itinerary);
        if (!subs.isEmpty()) {
            handleUrgentStatus(itinerary, subs, result, now);
            handleDayBookends(itinerary, subs, result, now);
            persistNotificationState(itinerary);
        }

        // 제안 생성은 별도 트랜잭션(ProposalService)에서 자신만의 Itinerary 사본을 읽고 저장한다 -
        // persistNotificationState() 이후에 호출해야 이 메서드가 들고 있는(제안 필드를 모르는)
        // 사본으로 나중에 덮어써서 방금 만든 제안을 없애 버리는 사고를 피한다(2026-09-15 핸드오프
        // 브리프 작업 중 발견).
        if (result.isRouteTangleTrigger()) {
            try {
                boolean freshProposal = proposalService.generateRouteProposalIfNeeded(itinerary.getId());
                // 긴급(DANGER)일 때만 제안 전용 푸시 - 나머지는 인앱 카드로만 노출(핸드오프 브리프
                // 결정 #5). fresh=true는 "새 추천으로 바뀐 순간"만 가리키므로(직전 제안과 내용이
                // 같으면 false) 같은 제안을 붙잡고 있는 동안 2분마다 재발송되지 않는다 - 별도
                // dedup 없이도 handleUrgentStatus와 같은 급의 "새 이벤트"로 취급해도 안전하다.
                if (freshProposal && result.getLevel() == TriggerLevel.DANGER && !subs.isEmpty()) {
                    notifyRouteProposal(itinerary, subs, result, now);
                }
            } catch (Exception e) {
                log.warn("[Notification] itinerary={} 동선 제안 생성 실패: {}", itinerary.getId(), e.toString());
            }
        }
    }

    /**
     * handleUrgentStatus/handleDayBookends가 이 tick에서 실제로 바꾼 4개 필드만 최신 엔티티에
     * 반영해 저장한다 - staleSnapshot을 통째로 save()하지 않는다.
     *
     * <p>detectForItinerary가 최대 20초 블로킹하는 동안 사용자가 ProposalService.accept/reject를
     * 호출해 pendingProposal·routeProposalCooldownUntil을 바꾸고 먼저 커밋할 수 있다. 그 뒤 이
     * 메서드가 tick 시작 시점(20초 전)의 스냅샷을 그대로 save()하면 방금 커밋된 변경을 덮어써
     * 사용자가 막 승인/거절한 제안이 되살아난다(2026-09-15 코드 리뷰에서 발견 - pendingProposal
     * 생성 경로는 이미 이 패턴으로 고쳐뒀었는데 이 일반 save 경로는 놓쳤었다). findById로 다시 읽어
     * 그 사이의 변경 위에 이 tick의 결과만 얹는다 - 경쟁 창을 20초에서 findById~save 사이의
     * 수 밀리초로 줄인다(낙관적 락 없이 완전히 없애려면 @Version이 필요하지만 이 앱 규모에선
     * 과함).
     */
    private void persistNotificationState(Itinerary staleSnapshot) {
        itineraryRepository.findById(staleSnapshot.getId()).ifPresent(fresh -> {
            fresh.setLastKnownTriggerLevel(staleSnapshot.getLastKnownTriggerLevel());
            fresh.setLastKnownTriggerSignature(staleSnapshot.getLastKnownTriggerSignature());
            fresh.setDayStartNotified(staleSnapshot.isDayStartNotified());
            fresh.setDayEndNotified(staleSnapshot.isDayEndNotified());
            itineraryRepository.save(fresh);
        });
    }

    /** 긴급 동선 제안 전용 푸시 - handleUrgentStatus의 일반 "상태 변화" 알림과는 별개 채널(다른
     *  nudgeId, 별도 dedup 필드)이라 같은 틱에 둘 다 뜰 수 있다. DANGER는 route 단독으로는 못 오르고
     *  (비·폭염·혼잡 긴급 등과 겹쳐야 함) 실제로는 드물게만 겹쳐 뜨므로 허용했다(2026-09-15
     *  핸드오프 브리프 P2). */
    private void notifyRouteProposal(Itinerary itinerary, List<PushSubscription> subs, TriggerResult result,
                                     LocalDateTime now) {
        String title = "🔴 동선을 다시 짤 수 있어요";
        String body = "이동을 줄일 수 있는 새 동선을 제안했어요. 앱에서 확인하고 적용해보세요.";
        recordAlertEvent(itinerary, "PROPOSAL", result, title, body, now, null);
        dispatch(itinerary, subs, title, body, "PROPOSAL:ROUTE@" + minuteKey(now), now, false, null, true);
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
        Long affectedItemId = primaryAffectedItemId(result);
        recordAlertEvent(itinerary, "STATUS", result, title, body, now, affectedItemId);
        dispatch(itinerary, subs, title, body, nudgeId, now, false, affectedItemId);
    }

    /** affectedItemId가 가리키는 항목의 contentId·장소명 - CTA용 슬롯 컨텍스트 스냅샷(대안 카드 없으면 둘 다 null) */
    private static ItineraryItem findById(Itinerary itinerary, Long itemId) {
        if (itemId == null) {
            return null;
        }
        return itinerary.getItems().stream()
                .filter(item -> itemId.equals(item.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 알림을 눌렀을 때 바로 그 카드로 스크롤+하이라이트하기 위한 "문제 발생한 장소" 1곳 - 가장 급한
     * 원인 순으로 훑어 처음 걸리는 항목을 쓴다(2026-09-11 사용자 요청: 상태 악화 알림은 해당 장소
     * 카드로 직행). 여러 곳이 동시에 걸려도 알림 문구(composer.statusBody)가 대표하는 곳과 정확히
     * 일치한다는 보장은 없지만, 없는 것보다 "가장 급한 곳 하나"가 훨씬 유용하다.
     */
    private static Long primaryAffectedItemId(TriggerResult result) {
        for (List<Long> ids : List.of(
                nullToEmpty(result.getCrowdAffectedItemIds()),
                nullToEmpty(result.getWeatherAffectedItemIds()),
                nullToEmpty(result.getClosedDayAffectedItemIds()),
                nullToEmpty(result.getHoursEndedAffectedItemIds()),
                nullToEmpty(result.getAffectedItemIds()))) {
            if (!ids.isEmpty()) {
                return ids.get(0);
            }
        }
        return null;
    }

    private static List<Long> nullToEmpty(List<Long> list) {
        return list == null ? List.of() : list;
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
                recordBookend(itinerary, "DAY_START", title, body, now);
                dispatch(itinerary, subs, title, body, "DAY_START@" + minuteKey(now), now, false, null);
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
                recordBookend(itinerary, "DAY_END", title, body, now);
                dispatch(itinerary, subs, title, body, "DAY_END@" + minuteKey(now), now, true, null);
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
                          String nudgeId, LocalDateTime now, boolean finish, Long highlightItemId) {
        dispatch(itinerary, subs, title, body, nudgeId, now, finish, highlightItemId, false);
    }

    /**
     * @param proposalChannel true면 PushSubscription.lastProposalSentKey로 dedup한다(일반
     *                        lastSentKey와 별개 필드) - 같은 틱에 STATUS 알림과 제안 전용 알림이
     *                        둘 다 나가면 하나의 필드를 같이 쓸 때 나중 것이 먼저 것의 dedup
     *                        마커를 덮어써 버렸다(2026-09-15 코드 리뷰에서 발견).
     */
    private void dispatch(Itinerary itinerary, List<PushSubscription> subs, String title, String body,
                          String nudgeId, LocalDateTime now, boolean finish, Long highlightItemId,
                          boolean proposalChannel) {
        String todayKey = now.toLocalDate() + ":" + nudgeId;
        String url = "/?open=" + itinerary.getId()
                + (highlightItemId != null ? "&item=" + highlightItemId : "")
                + (finish ? "&finish=1" : "");
        Map<String, String> data = Map.of(
                "itineraryId", String.valueOf(itinerary.getId()),
                "url", url);
        for (PushSubscription sub : subs) {
            String lastKey = proposalChannel ? sub.getLastProposalSentKey() : sub.getLastSentKey();
            if (todayKey.equals(lastKey)) {
                log.info("[Notification] 중복 스킵 itinerary={} nudgeId={}", itinerary.getId(), nudgeId);
                continue;
            }
            boolean sent = pushSenderService.send(sub.getFcmToken(), title, body, data);
            log.info("[Notification] itinerary={} nudgeId={} sent={}", itinerary.getId(), nudgeId, sent);
            if (proposalChannel) {
                sub.setLastProposalSentKey(todayKey);
            } else {
                sub.setLastSentKey(todayKey);
            }
            pushSubscriptionRepository.save(sub);
        }
    }

    private void recordAlertEvent(Itinerary itinerary, String kind, TriggerResult result, String title, String body,
                                  LocalDateTime now, Long affectedItemId) {
        ItineraryItem affected = findById(itinerary, affectedItemId);
        alertEventRepository.save(AlertEvent.builder()
                .itineraryId(itinerary.getId())
                .kind(kind)
                .level(result.getLevel())
                .icon(AlertIconResolver.resolve(result))
                .headline(title)
                .detail(body)
                .affectedItemId(affected != null ? affected.getId() : null)
                .affectedContentId(affected != null ? affected.getContentId() : null)
                .affectedPlaceName(affected != null ? affected.getPlaceName() : null)
                .createdAt(KoreaClock.toUtcWall(now))
                .build());
    }

    private void recordBookend(Itinerary itinerary, String kind, String title, String body, LocalDateTime now) {
        alertEventRepository.save(AlertEvent.builder()
                .itineraryId(itinerary.getId())
                .kind(kind)
                .level(TriggerLevel.NORMAL)
                .icon("🟢")
                .headline(title)
                .detail(body)
                .createdAt(KoreaClock.toUtcWall(now))
                .build());
    }

    private static String minuteKey(LocalDateTime now) {
        return now.truncatedTo(ChronoUnit.MINUTES).toString();
    }

    record TripBounds(LocalTime firstStart, LocalTime lastEnd) {
    }
}
