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
import com.windmill.util.KoreaClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 순풍 북엔드(첫 일정 30분 전 / 마지막 종료)와 주황·빨강 즉시 알림 판정 검증.
 */
class NotificationSchedulerServiceTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 8, 20, 0, 0);
    /** 첫 일정 10:00 기준 30분 전 창 */
    private static final LocalDateTime LEAD_NOW = LocalDateTime.of(2026, 8, 20, 9, 32);
    /** 관광 기본 체류 75분 → 10:00 시작이면 점유 종료 11:15 */
    private static final LocalDateTime AFTER_END = LocalDateTime.of(2026, 8, 20, 11, 16);
    private static final LocalDateTime MID_TRIP = LocalDateTime.of(2026, 8, 20, 10, 5);

    private ItineraryRepository itineraryRepository;
    private PushSubscriptionRepository pushSubscriptionRepository;
    private AlertEventRepository alertEventRepository;
    private TriggerDetectionService triggerDetectionService;
    private PushSenderService pushSenderService;
    private ProposalService proposalService;
    private NotificationSchedulerService scheduler;

    @BeforeEach
    void setUp() {
        itineraryRepository = mock(ItineraryRepository.class);
        pushSubscriptionRepository = mock(PushSubscriptionRepository.class);
        alertEventRepository = mock(AlertEventRepository.class);
        triggerDetectionService = mock(TriggerDetectionService.class);
        pushSenderService = mock(PushSenderService.class);
        proposalService = mock(ProposalService.class);
        scheduler = new NotificationSchedulerService(
                itineraryRepository, pushSubscriptionRepository, alertEventRepository, triggerDetectionService,
                new NotificationComposer(), pushSenderService, proposalService);
        when(pushSenderService.send(anyString(), anyString(), anyString(), anyMap())).thenReturn(true);
    }

    private Itinerary itineraryWithItems(ItineraryItem... items) {
        return Itinerary.builder()
                .id(1L)
                .sessionUuid("session-1")
                .signguFullCode("51210")
                .regionDisplayName("강원특별자치도 속초시")
                .startDate(DAY.toLocalDate())
                .endDate(DAY.toLocalDate())
                .items(new ArrayList<>(List.of(items)))
                .build();
    }

    private ItineraryItem placeAt(String time) {
        return ItineraryItem.builder().id(10L).placeName("속초등대전망대")
                .category("관광지").scheduledTime(time).visitDate(DAY.toLocalDate()).build();
    }

    private void stubActive(Itinerary itinerary, LocalDateTime at) {
        when(itineraryRepository.findActiveTodayForNotification(at.toLocalDate())).thenReturn(List.of(itinerary));
    }

    private void stubSubscriptions(PushSubscription... subs) {
        when(pushSubscriptionRepository.findByItineraryId(1L)).thenReturn(List.of(subs));
    }

    private void stubTrigger(TriggerResult result) {
        when(triggerDetectionService.detectForItinerary(any(Itinerary.class))).thenReturn(Mono.just(result));
    }

    private void stubTrigger(TriggerLevel level, String... details) {
        stubTrigger(TriggerResult.builder().level(level).triggerDetails(List.of(details)).build());
    }

    private PushSubscription sub(String token) {
        return PushSubscription.builder().id(1L).sessionUuid("session-1").fcmToken(token).itineraryId(1L).build();
    }

    @Test
    void firstObservationWarning_sendsImmediately() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        stubActive(itinerary, MID_TRIP);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerResult.builder()
                .level(TriggerLevel.WARNING)
                .weatherTrigger(true)
                .triggerDetails(List.of("비 소식이 있어요. 야외 일정을 실내 코스로 바꿔보세요."))
                .build());

        scheduler.runTick(MID_TRIP);

        verify(pushSenderService, times(1)).send(eq("token-1"),
                eq("🟠 여행에 변수가 생겼어요"), anyString(), anyMap());
        assertEquals(TriggerLevel.WARNING, itinerary.getLastKnownTriggerLevel());
        assertEquals("RAIN", itinerary.getLastKnownTriggerSignature());
    }

    @Test
    void urgentStatus_includesPrimaryAffectedItemIdInUrl() {
        // 2026-09-11 사용자 제보 - 알림을 눌러도 문제 장소 카드로 안 감. crowdAffectedItemIds가
        // 있으면 그중 첫 항목을 "item=" 쿼리로 실어 프론트가 그 카드로 스크롤+하이라이트할 수 있게 한다.
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        stubActive(itinerary, MID_TRIP);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerResult.builder()
                .level(TriggerLevel.WARNING)
                .crowdTrigger(true)
                .crowdAffectedItemIds(List.of(42L, 99L))
                .triggerDetails(List.of("혼잡해요"))
                .build());

        scheduler.runTick(MID_TRIP);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(pushSenderService, times(1)).send(eq("token-1"), anyString(), anyString(), data.capture());
        assertTrue(data.getValue().get("url").contains("&item=42"));
    }

    /**
     * 2026-09-14 핸드오프 브리프 Phase 3/6 - 알림 카드의 "대체 장소 고르기" CTA가 어느 슬롯을 가리키는지
     * AlertEvent에 스냅샷으로 남아야, 나중에 그 슬롯이 다른 장소로 교체돼도(D5) 프론트가 판단할 수 있다.
     */
    @Test
    void urgentStatus_snapshotsAffectedItemContextOnAlertEvent() {
        ItineraryItem museum = ItineraryItem.builder()
                .id(42L).placeName("부엉이전시관").contentId("c-owl").category("문화시설")
                .scheduledTime("10:00").visitDate(DAY.toLocalDate()).build();
        Itinerary itinerary = itineraryWithItems(museum);
        stubActive(itinerary, MID_TRIP);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerResult.builder()
                .level(TriggerLevel.DANGER)
                .closedDayTrigger(true)
                .closedDayAffectedItemIds(List.of(42L))
                .triggerDetails(List.of("부엉이전시관이 오늘 휴무예요. 대체 장소를 골라보세요."))
                .build());

        scheduler.runTick(MID_TRIP);

        ArgumentCaptor<AlertEvent> saved = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepository).save(saved.capture());
        assertEquals(42L, saved.getValue().getAffectedItemId());
        assertEquals("c-owl", saved.getValue().getAffectedContentId());
        assertEquals("부엉이전시관", saved.getValue().getAffectedPlaceName());
    }

    @Test
    void firstObservationWarning_usesWeatherFlagInSignature() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        stubActive(itinerary, MID_TRIP);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerResult.builder()
                .level(TriggerLevel.DANGER)
                .weatherTrigger(true)
                .triggerDetails(List.of("비 소식이 있어요. 야외 일정을 실내 코스로 바꿔보세요."))
                .build());

        scheduler.runTick(MID_TRIP);

        verify(pushSenderService, times(1)).send(eq("token-1"),
                eq("🔴 지금 코스를 바꿔야 해요"), anyString(), anyMap());
        assertEquals("RAIN", itinerary.getLastKnownTriggerSignature());
    }

    @Test
    void sameWarningSignature_doesNotResend() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        itinerary.setLastKnownTriggerLevel(TriggerLevel.WARNING);
        itinerary.setLastKnownTriggerSignature("RAIN");
        stubActive(itinerary, MID_TRIP);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerResult.builder()
                .level(TriggerLevel.WARNING)
                .weatherTrigger(true)
                .triggerDetails(List.of("비 소식이 있어요. 야외 일정을 실내 코스로 바꿔보세요."))
                .build());

        scheduler.runTick(MID_TRIP);

        verify(pushSenderService, never()).send(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void newCrowdOnTopOfRain_sendsAgain() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        itinerary.setLastKnownTriggerLevel(TriggerLevel.WARNING);
        itinerary.setLastKnownTriggerSignature("RAIN");
        stubActive(itinerary, MID_TRIP);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerResult.builder()
                .level(TriggerLevel.DANGER)
                .weatherTrigger(true)
                .crowdTrigger(true)
                .triggerDetails(List.of(
                        "비 소식이 있어요. 야외 일정을 실내 코스로 바꿔보세요.",
                        "혼잡도가 높아요. 여유로운 곳으로 바꿔볼까요?"))
                .build());

        scheduler.runTick(MID_TRIP);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(pushSenderService, times(1)).send(eq("token-1"),
                eq("🔴 지금 코스를 바꿔야 해요"), body.capture(), anyMap());
        assertTrue(body.getValue().contains("비 소식이 있어요."));
        assertTrue(body.getValue().contains("혼잡도가 높아요."));
        assertEquals("RAIN,CROWD", itinerary.getLastKnownTriggerSignature());
    }

    @Test
    void warningToDangerSameFlags_sendsBecauseLevelWorsened() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        itinerary.setLastKnownTriggerLevel(TriggerLevel.WARNING);
        itinerary.setLastKnownTriggerSignature("HEAT");
        stubActive(itinerary, MID_TRIP);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerResult.builder()
                .level(TriggerLevel.DANGER)
                .heatTrigger(true)
                .heatUrgent(true)
                .triggerDetails(List.of("최고기온 35℃ 이상(폭염경보 수준)이에요. 야외는 짧게, 실내 코스로 바꿔 보세요."))
                .build());

        scheduler.runTick(MID_TRIP);

        verify(pushSenderService, times(1)).send(eq("token-1"),
                eq("🔴 지금 코스를 바꿔야 해요"), anyString(), anyMap());
        assertEquals("HEAT_URGENT", itinerary.getLastKnownTriggerSignature());
    }

    @Test
    void firstObservationNormal_setsBaselineWithoutSending() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        stubActive(itinerary, MID_TRIP);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerLevel.NORMAL);

        scheduler.runTick(MID_TRIP);

        verify(pushSenderService, never()).send(anyString(), anyString(), anyString(), anyMap());
        assertEquals(TriggerLevel.NORMAL, itinerary.getLastKnownTriggerLevel());
        // MID_TRIP(10:05)은 첫 일정 10:00 기준 순풍 리드 창(09:30~10:00)을 이미 지난 시각이라,
        // 발송은 없지만 다음 틱에서 재평가하지 않도록 dayStartNotified만 마킹된다(scheduler line 187-188).
        assertTrue(itinerary.isDayStartNotified());
        assertFalse(itinerary.isDayEndNotified());
    }

    @Test
    void dayStart_thirtyMinutesBeforeFirstSchedule_sendsFairWindCopy() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        itinerary.setLastKnownTriggerLevel(TriggerLevel.NORMAL);
        stubActive(itinerary, LEAD_NOW);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerLevel.NORMAL);

        scheduler.runTick(LEAD_NOW);

        verify(pushSenderService, times(1)).send(eq("token-1"),
                eq("🟢 오늘 일정 시작 30분 전입니다"),
                eq("오늘 일정 시작 30분 전입니다. 순풍이 부니 바람따라 여행해주세요."),
                anyMap());
        assertTrue(itinerary.isDayStartNotified());
        ArgumentCaptor<AlertEvent> saved = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepository).save(saved.capture());
        assertEquals("DAY_START", saved.getValue().getKind());
        assertEquals(KoreaClock.toUtcWall(LEAD_NOW), saved.getValue().getCreatedAt());
    }

    @Test
    void dayStart_tooEarly_doesNotSend() {
        LocalDateTime tooEarly = LocalDateTime.of(2026, 8, 20, 9, 0);
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        itinerary.setLastKnownTriggerLevel(TriggerLevel.NORMAL);
        stubActive(itinerary, tooEarly);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerLevel.NORMAL);

        scheduler.runTick(tooEarly);

        verify(pushSenderService, never()).send(anyString(), anyString(), anyString(), anyMap());
        assertFalse(itinerary.isDayStartNotified());
    }

    @Test
    void dayStart_afterFirstSchedule_marksWithoutSending() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        itinerary.setLastKnownTriggerLevel(TriggerLevel.NORMAL);
        stubActive(itinerary, MID_TRIP);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerLevel.NORMAL);

        scheduler.runTick(MID_TRIP);

        verify(pushSenderService, never()).send(anyString(), anyString(), anyString(), anyMap());
        assertTrue(itinerary.isDayStartNotified());
    }

    @Test
    void dayStart_skippedWhenWindIsOrange() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        stubActive(itinerary, LEAD_NOW);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerResult.builder()
                .level(TriggerLevel.WARNING)
                .crowdTrigger(true)
                .triggerDetails(List.of("혼잡도가 높아요. 여유로운 곳으로 바꿔볼까요?"))
                .build());

        scheduler.runTick(LEAD_NOW);

        verify(pushSenderService, times(1)).send(eq("token-1"),
                eq("🟠 여행에 변수가 생겼어요"), anyString(), anyMap());
        assertFalse(itinerary.isDayStartNotified());
    }

    @Test
    void dayEnd_afterLastOccupancy_sendsWrapUpAndFinishLink() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        itinerary.setLastKnownTriggerLevel(TriggerLevel.NORMAL);
        itinerary.setDayStartNotified(true);
        stubActive(itinerary, AFTER_END);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerLevel.NORMAL);

        scheduler.runTick(AFTER_END);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(pushSenderService, times(1)).send(eq("token-1"),
                eq("🟢 오늘 모든 일정을 마칩니다"),
                eq("오늘 모든 일정을 마칩니다. 여행 마무리를 남겨주세요."),
                data.capture());
        assertTrue(data.getValue().get("url").contains("finish=1"));
        assertTrue(itinerary.isDayEndNotified());
        ArgumentCaptor<AlertEvent> saved = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepository).save(saved.capture());
        assertEquals("DAY_END", saved.getValue().getKind());
        assertEquals(KoreaClock.toUtcWall(AFTER_END), saved.getValue().getCreatedAt());
    }

    @Test
    void dayEnd_beforeLastOccupancy_doesNotSend() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        itinerary.setLastKnownTriggerLevel(TriggerLevel.NORMAL);
        itinerary.setDayStartNotified(true);
        stubActive(itinerary, MID_TRIP);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerLevel.NORMAL);

        scheduler.runTick(MID_TRIP);

        verify(pushSenderService, never()).send(anyString(), anyString(), anyString(), anyMap());
        assertFalse(itinerary.isDayEndNotified());
    }

    @Test
    void dayEnd_orangeWind_doesNotSendWrapUpYet() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        itinerary.setLastKnownTriggerLevel(TriggerLevel.WARNING);
        itinerary.setLastKnownTriggerSignature("RAIN");
        itinerary.setDayStartNotified(true);
        stubActive(itinerary, AFTER_END);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerResult.builder()
                .level(TriggerLevel.WARNING)
                .weatherTrigger(true)
                .triggerDetails(List.of("비 소식이 있어요. 야외 일정을 실내 코스로 바꿔보세요."))
                .build());

        scheduler.runTick(AFTER_END);

        verify(pushSenderService, never()).send(anyString(), anyString(), anyString(), anyMap());
        assertFalse(itinerary.isDayEndNotified());
    }

    @Test
    void dayEnd_usesLatestOccupancyAmongItems() {
        ItineraryItem first = placeAt("10:00");
        ItineraryItem last = ItineraryItem.builder().id(11L).placeName("속초해변")
                .category("관광지").scheduledTime("14:00").visitDate(DAY.toLocalDate()).build();
        Itinerary itinerary = itineraryWithItems(first, last);
        itinerary.setLastKnownTriggerLevel(TriggerLevel.NORMAL);
        itinerary.setDayStartNotified(true);
        LocalDateTime tooSoon = LocalDateTime.of(2026, 8, 20, 12, 0); // 첫 슬롯은 끝났지만 마지막(15:15) 전
        stubActive(itinerary, tooSoon);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerLevel.NORMAL);

        scheduler.runTick(tooSoon);

        verify(pushSenderService, never()).send(anyString(), anyString(), anyString(), anyMap());
        assertFalse(itinerary.isDayEndNotified());
    }

    @Test
    void dayStart_usesEarliestRegisteredTime() {
        ItineraryItem later = ItineraryItem.builder().id(11L).placeName("점심")
                .category("맛집").scheduledTime("12:00").visitDate(DAY.toLocalDate()).build();
        Itinerary itinerary = itineraryWithItems(later, placeAt("10:00"));
        itinerary.setLastKnownTriggerLevel(TriggerLevel.NORMAL);
        stubActive(itinerary, LEAD_NOW);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerLevel.NORMAL);

        scheduler.runTick(LEAD_NOW);

        verify(pushSenderService, times(1)).send(eq("token-1"),
                eq("🟢 오늘 일정 시작 30분 전입니다"), anyString(), anyMap());
    }

    /**
     * 2026-09-15 핸드오프 브리프(동선 변경 승인제 전환) 이후: 푸시 구독이 없어도 상태 감지·제안 생성은
     * 그대로 돈다(알림 발송만 구독에 의존) - 그래야 푸시 권한을 안 준 사용자도 제안 카드를 받는다.
     * 예전엔 구독이 없으면 감지 자체를 건너뛰었다(noSubscriptions_skipsTriggerDetectionEntirely).
     */
    @Test
    void noSubscriptions_stillDetectsAndProposesButSkipsPush() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        stubActive(itinerary, LEAD_NOW);
        when(pushSubscriptionRepository.findByItineraryId(1L)).thenReturn(List.of());
        stubTrigger(TriggerResult.builder()
                .level(TriggerLevel.WARNING)
                .routeTangleTrigger(true)
                .triggerDetails(List.of("동선이 꼬였어요."))
                .build());

        scheduler.runTick(LEAD_NOW);

        verify(triggerDetectionService, times(1)).detectForItinerary(any(Itinerary.class));
        verify(proposalService, times(1)).generateRouteProposalIfNeeded(1L);
        verify(pushSenderService, never()).send(anyString(), anyString(), anyString(), anyMap());
    }

    /** P2(핸드오프 브리프 결정 #5): 새 제안이 긴급(DANGER)일 때만 제안 전용 푸시를 보낸다. */
    @Test
    void freshRouteProposal_atDangerLevel_sendsProposalPush() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        stubActive(itinerary, MID_TRIP);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerResult.builder()
                .level(TriggerLevel.DANGER)
                .routeTangleTrigger(true)
                .weatherTrigger(true)
                .triggerDetails(List.of("동선이 꼬였어요.", "비 소식이 있어요."))
                .build());
        when(proposalService.generateRouteProposalIfNeeded(1L)).thenReturn(true);

        scheduler.runTick(MID_TRIP);

        verify(pushSenderService, times(1)).send(eq("token-1"),
                eq("🔴 동선을 다시 짤 수 있어요"), anyString(), anyMap());
    }

    /** 제안이 새로 생겼어도 WARNING이면(긴급 아님) 제안 전용 푸시는 안 보낸다 - 인앱 카드로만. */
    @Test
    void freshRouteProposal_atWarningLevel_doesNotSendProposalPush() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        stubActive(itinerary, MID_TRIP);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerResult.builder()
                .level(TriggerLevel.WARNING)
                .routeTangleTrigger(true)
                .triggerDetails(List.of("동선이 꼬였어요."))
                .build());
        when(proposalService.generateRouteProposalIfNeeded(1L)).thenReturn(true);

        scheduler.runTick(MID_TRIP);

        verify(pushSenderService, never()).send(anyString(),
                eq("🔴 동선을 다시 짤 수 있어요"), anyString(), anyMap());
    }

    /** 추천 순서가 직전 제안과 같아 generateRouteProposalIfNeeded가 false를 돌려주면(새 제안 아님)
     *  DANGER여도 재발송하지 않는다 - 같은 제안을 붙잡고 있는 동안 2분마다 스팸이 안 되게. */
    @Test
    void nonFreshRouteProposal_atDangerLevel_doesNotSendProposalPush() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        stubActive(itinerary, MID_TRIP);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerResult.builder()
                .level(TriggerLevel.DANGER)
                .routeTangleTrigger(true)
                .weatherTrigger(true)
                .triggerDetails(List.of("동선이 꼬였어요.", "비 소식이 있어요."))
                .build());
        when(proposalService.generateRouteProposalIfNeeded(1L)).thenReturn(false);

        scheduler.runTick(MID_TRIP);

        verify(pushSenderService, never()).send(anyString(),
                eq("🔴 동선을 다시 짤 수 있어요"), anyString(), anyMap());
    }

    @Test
    void sessionOnlySubscription_withoutItineraryId_stillSendsUrgent() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        stubActive(itinerary, MID_TRIP);
        stubTrigger(TriggerResult.builder()
                .level(TriggerLevel.WARNING)
                .routeTangleTrigger(true)
                .triggerDetails(List.of("동선이 꼬였어요. 자동 재배치로 이동을 줄여 보세요."))
                .build());
        when(pushSubscriptionRepository.findByItineraryId(1L)).thenReturn(List.of());
        PushSubscription sessionSub = PushSubscription.builder()
                .id(2L).sessionUuid("session-1").fcmToken("token-session").itineraryId(null).build();
        when(pushSubscriptionRepository.findBySessionUuid("session-1")).thenReturn(List.of(sessionSub));

        scheduler.runTick(MID_TRIP);

        verify(pushSenderService, times(1)).send(eq("token-session"), anyString(), anyString(), anyMap());
    }

    @Test
    void sameTokenOnItineraryAndSession_sendsOnce() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        stubActive(itinerary, LEAD_NOW);
        stubTrigger(TriggerLevel.NORMAL);
        PushSubscription itinerarySub = sub("token-shared");
        PushSubscription sessionSub = PushSubscription.builder()
                .id(9L).sessionUuid("session-1").fcmToken("token-shared").itineraryId(null).build();
        when(pushSubscriptionRepository.findByItineraryId(1L)).thenReturn(List.of(itinerarySub));
        when(pushSubscriptionRepository.findBySessionUuid("session-1")).thenReturn(List.of(sessionSub));

        scheduler.runTick(LEAD_NOW);

        verify(pushSenderService, times(1)).send(eq("token-shared"), anyString(), anyString(), anyMap());
    }

    @Test
    void dedup_alreadySentSubscriptionSkippedOthersStillSent() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        itinerary.setLastKnownTriggerLevel(TriggerLevel.NORMAL);
        stubActive(itinerary, LEAD_NOW);
        stubTrigger(TriggerLevel.NORMAL);

        PushSubscription alreadySent = sub("token-already-sent");
        alreadySent.setLastSentKey("2026-08-20:DAY_START@2026-08-20T09:32");
        PushSubscription fresh = sub("token-fresh");
        stubSubscriptions(alreadySent, fresh);

        scheduler.runTick(LEAD_NOW);

        verify(pushSenderService, never()).send(eq("token-already-sent"), anyString(), anyString(), anyMap());
        verify(pushSenderService, times(1)).send(eq("token-fresh"), anyString(), anyString(), anyMap());
    }

    @Test
    void recoveredToNormalThenWarningAgain_sendsAgain() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        itinerary.setLastKnownTriggerLevel(TriggerLevel.NORMAL);
        itinerary.setLastKnownTriggerSignature("");
        itinerary.setDayStartNotified(true);
        stubActive(itinerary, MID_TRIP);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerResult.builder()
                .level(TriggerLevel.WARNING)
                .crowdTrigger(true)
                .triggerDetails(List.of("혼잡도가 높아요. 여유로운 곳으로 바꿔볼까요?"))
                .build());

        scheduler.runTick(MID_TRIP);

        verify(pushSenderService, times(1)).send(eq("token-1"),
                eq("🟠 여행에 변수가 생겼어요"), anyString(), anyMap());
    }

    /**
     * 2026-09-15 코드 리뷰에서 발견한 회귀 방지 테스트 - detectForItinerary가 블로킹하는 동안(최대
     * 20초) 다른 트랜잭션(ProposalService.accept/reject)이 pendingProposal을 먼저 바꿔 커밋했다면,
     * 이 tick이 tick-시작 시점의 스냅샷을 그대로 save()해서 그 변경을 덮어쓰면 안 된다.
     */
    @Test
    void concurrentProposalChange_duringBlockingDetect_isNotClobberedByStaleSave() {
        Itinerary tickStartSnapshot = itineraryWithItems(placeAt("10:00"));
        stubActive(tickStartSnapshot, LEAD_NOW);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerLevel.NORMAL);

        // ProposalService.accept/reject가 이미 커밋해 둔 "실제" 최신 DB 상태를 흉내낸다 - 스케줄러가
        // 들고 있던 tick-시작 시점 스냅샷과는 별개 인스턴스, pendingProposal이 채워져 있다.
        Itinerary concurrentlyUpdated = itineraryWithItems(placeAt("10:00"));
        concurrentlyUpdated.setPendingProposal(
                com.windmill.dto.PendingProposal.builder().proposalId("p1").trigger("ROUTE").build());
        when(itineraryRepository.findById(1L)).thenReturn(java.util.Optional.of(concurrentlyUpdated));

        scheduler.runTick(LEAD_NOW);

        ArgumentCaptor<Itinerary> captor = ArgumentCaptor.forClass(Itinerary.class);
        verify(itineraryRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        boolean anySavedKeepsProposal = captor.getAllValues().stream()
                .anyMatch(saved -> saved.getPendingProposal() != null);
        org.junit.jupiter.api.Assertions.assertTrue(anySavedKeepsProposal,
                "동시에 커밋된 pendingProposal이 tick의 저장으로 지워지면 안 된다");
    }

    @Test
    void skipsItineraryWhenStartDateIsNotTheTickDay() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        itinerary.setStartDate(DAY.toLocalDate().plusDays(3));
        stubActive(itinerary, MID_TRIP);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerLevel.DANGER, "비 소식이 있어요.");

        scheduler.runTick(MID_TRIP);

        verifyNoInteractions(pushSenderService);
        verify(triggerDetectionService, never()).detectForItinerary(any(Itinerary.class));
    }
}
