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
    private NotificationSchedulerService scheduler;

    @BeforeEach
    void setUp() {
        itineraryRepository = mock(ItineraryRepository.class);
        pushSubscriptionRepository = mock(PushSubscriptionRepository.class);
        alertEventRepository = mock(AlertEventRepository.class);
        triggerDetectionService = mock(TriggerDetectionService.class);
        pushSenderService = mock(PushSenderService.class);
        scheduler = new NotificationSchedulerService(
                itineraryRepository, pushSubscriptionRepository, alertEventRepository, triggerDetectionService,
                new NotificationComposer(), pushSenderService);
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

    @Test
    void noSubscriptions_skipsTriggerDetectionEntirely() {
        Itinerary itinerary = itineraryWithItems(placeAt("10:00"));
        stubActive(itinerary, LEAD_NOW);
        when(pushSubscriptionRepository.findByItineraryId(1L)).thenReturn(List.of());

        scheduler.runTick(LEAD_NOW);

        verifyNoInteractions(triggerDetectionService);
        verify(pushSenderService, never()).send(anyString(), anyString(), anyString(), anyMap());
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
