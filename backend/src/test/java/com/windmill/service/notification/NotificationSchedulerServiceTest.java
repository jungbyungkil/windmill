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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * NotificationSchedulerService의 판단 로직(정기/슬롯/상태악화 + 병합/우선순위/중복방지) 검증.
 * Firebase 미설정 상태에서도 "발송을 시도했는가"만 검증하면 되므로 PushSenderService는 목킹한다
 * (send()는 미설정 시 안전하게 false를 반환할 뿐 예외를 던지지 않음 - 실제 코드도 이 계약에 의존).
 */
class NotificationSchedulerServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 20, 10, 5);

    private ItineraryRepository itineraryRepository;
    private PushSubscriptionRepository pushSubscriptionRepository;
    private TriggerDetectionService triggerDetectionService;
    private PushSenderService pushSenderService;
    private NotificationSchedulerService scheduler;

    @BeforeEach
    void setUp() {
        itineraryRepository = mock(ItineraryRepository.class);
        pushSubscriptionRepository = mock(PushSubscriptionRepository.class);
        triggerDetectionService = mock(TriggerDetectionService.class);
        pushSenderService = mock(PushSenderService.class);
        scheduler = new NotificationSchedulerService(
                itineraryRepository, pushSubscriptionRepository, triggerDetectionService,
                new NotificationComposer(), pushSenderService);
        when(pushSenderService.send(anyString(), anyString(), anyString(), anyMap())).thenReturn(true);
    }

    private Itinerary itineraryWithItems(ItineraryItem... items) {
        return Itinerary.builder()
                .id(1L)
                .sessionUuid("session-1")
                .signguFullCode("51210")
                .regionDisplayName("강원특별자치도 속초시")
                .startDate(NOW.toLocalDate())
                .endDate(NOW.toLocalDate())
                .items(new ArrayList<>(List.of(items)))
                .build();
    }

    private void stubActive(Itinerary itinerary) {
        when(itineraryRepository.findActiveTodayForNotification(NOW.toLocalDate())).thenReturn(List.of(itinerary));
    }

    private void stubSubscriptions(PushSubscription... subs) {
        when(pushSubscriptionRepository.findByItineraryId(1L)).thenReturn(List.of(subs));
    }

    private void stubTrigger(TriggerLevel level, String... details) {
        TriggerResult result = TriggerResult.builder().level(level).triggerDetails(List.of(details)).build();
        when(triggerDetectionService.detectForItinerary(any(Itinerary.class))).thenReturn(Mono.just(result));
    }

    private PushSubscription sub(String token) {
        return PushSubscription.builder().id(1L).sessionUuid("session-1").fcmToken(token).itineraryId(1L).build();
    }

    @Test
    void statusDegradation_sendsType3StandaloneAndUpdatesLevel() {
        Itinerary itinerary = itineraryWithItems();
        itinerary.setLastKnownTriggerLevel(TriggerLevel.NORMAL);
        itinerary.setLastPeriodicNotifiedAt(NOW.minusMinutes(5)); // 정기 알림은 아직 안 겹치게
        stubActive(itinerary);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerLevel.WARNING, "비 소식이 있어요. 야외 일정을 실내 코스로 바꿔보세요.");

        scheduler.runTick(NOW);

        verify(pushSenderService, times(1)).send(eq("token-1"), anyString(), anyString(), anyMap());
        assertEquals(TriggerLevel.WARNING, itinerary.getLastKnownTriggerLevel());
    }

    @Test
    void levelUnchanged_noType3Send() {
        Itinerary itinerary = itineraryWithItems();
        itinerary.setLastKnownTriggerLevel(TriggerLevel.WARNING);
        itinerary.setLastPeriodicNotifiedAt(NOW.minusMinutes(5));
        stubActive(itinerary);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerLevel.WARNING, "혼잡도가 높아요. 여유로운 곳으로 바꿔볼까요?");

        scheduler.runTick(NOW);

        verify(pushSenderService, never()).send(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void firstObservation_setsBaselineWithoutSending() {
        Itinerary itinerary = itineraryWithItems();
        itinerary.setLastKnownTriggerLevel(null);
        itinerary.setLastPeriodicNotifiedAt(NOW.minusMinutes(5));
        stubActive(itinerary);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerLevel.WARNING, "혼잡도가 높아요. 여유로운 곳으로 바꿔볼까요?");

        scheduler.runTick(NOW);

        verify(pushSenderService, never()).send(anyString(), anyString(), anyString(), anyMap());
        assertEquals(TriggerLevel.WARNING, itinerary.getLastKnownTriggerLevel());
    }

    @Test
    void periodicDue_sendsAndUpdatesTimestamp() {
        Itinerary itinerary = itineraryWithItems();
        itinerary.setLastKnownTriggerLevel(TriggerLevel.NORMAL);
        itinerary.setLastPeriodicNotifiedAt(null);
        stubActive(itinerary);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerLevel.NORMAL);

        scheduler.runTick(NOW);

        verify(pushSenderService, times(1)).send(eq("token-1"), anyString(), anyString(), anyMap());
        assertEquals(NOW, itinerary.getLastPeriodicNotifiedAt());
    }

    @Test
    void periodicNotDue_noSend() {
        Itinerary itinerary = itineraryWithItems();
        itinerary.setLastKnownTriggerLevel(TriggerLevel.NORMAL);
        itinerary.setLastPeriodicNotifiedAt(NOW.minusMinutes(10));
        stubActive(itinerary);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerLevel.NORMAL);

        scheduler.runTick(NOW);

        verify(pushSenderService, never()).send(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void slotReached_sendsAloneAndMarksNotified() {
        ItineraryItem item = ItineraryItem.builder().id(10L).placeName("속초등대전망대")
                .category("관광지").scheduledTime("10:00").slotNotified(false).build();
        Itinerary itinerary = itineraryWithItems(item);
        itinerary.setLastKnownTriggerLevel(TriggerLevel.NORMAL);
        itinerary.setLastPeriodicNotifiedAt(NOW.minusMinutes(5)); // 정기는 아직 안 겹치게
        stubActive(itinerary);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerLevel.NORMAL);

        scheduler.runTick(NOW);

        verify(pushSenderService, times(1)).send(eq("token-1"), anyString(), anyString(), anyMap());
        assertTrue(item.isSlotNotified());
    }

    @Test
    void periodicAndSlotSameTick_mergeIntoOneSend() {
        ItineraryItem item = ItineraryItem.builder().id(10L).placeName("속초등대전망대")
                .category("관광지").scheduledTime("10:00").slotNotified(false).build();
        Itinerary itinerary = itineraryWithItems(item);
        itinerary.setLastKnownTriggerLevel(TriggerLevel.NORMAL);
        itinerary.setLastPeriodicNotifiedAt(null); // 정기도 도달
        stubActive(itinerary);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerLevel.NORMAL);

        scheduler.runTick(NOW);

        verify(pushSenderService, times(1)).send(eq("token-1"), anyString(), anyString(), anyMap());
        assertTrue(item.isSlotNotified());
        assertEquals(NOW, itinerary.getLastPeriodicNotifiedAt());
    }

    @Test
    void statusDegradationIndependentFromPeriodicSlot_twoSeparateSends() {
        ItineraryItem item = ItineraryItem.builder().id(10L).placeName("속초등대전망대")
                .category("관광지").scheduledTime("10:00").slotNotified(false).build();
        Itinerary itinerary = itineraryWithItems(item);
        itinerary.setLastKnownTriggerLevel(TriggerLevel.NORMAL);
        itinerary.setLastPeriodicNotifiedAt(null); // 정기도 도달
        stubActive(itinerary);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerLevel.WARNING, "혼잡도가 높아요. 여유로운 곳으로 바꿔볼까요?"); // 악화 트리거

        scheduler.runTick(NOW);

        // Type3(상태악화) 1건 + Type1+2(정기+슬롯 병합) 1건 = 총 2건, 서로 병합되지 않는다
        verify(pushSenderService, times(2)).send(eq("token-1"), anyString(), anyString(), anyMap());
    }

    @Test
    void staleSlot_markedButNotSent() {
        ItineraryItem item = ItineraryItem.builder().id(10L).placeName("속초등대전망대")
                .category("관광지").scheduledTime("09:00").slotNotified(false).build(); // 65분 전 - 유예창 밖
        Itinerary itinerary = itineraryWithItems(item);
        itinerary.setLastKnownTriggerLevel(TriggerLevel.NORMAL);
        itinerary.setLastPeriodicNotifiedAt(NOW.minusMinutes(5));
        stubActive(itinerary);
        stubSubscriptions(sub("token-1"));
        stubTrigger(TriggerLevel.NORMAL);

        scheduler.runTick(NOW);

        verify(pushSenderService, never()).send(anyString(), anyString(), anyString(), anyMap());
        assertTrue(item.isSlotNotified());
    }

    @Test
    void noSubscriptions_skipsTriggerDetectionEntirely() {
        Itinerary itinerary = itineraryWithItems();
        stubActive(itinerary);
        when(pushSubscriptionRepository.findByItineraryId(1L)).thenReturn(List.of());

        scheduler.runTick(NOW);

        verifyNoInteractions(triggerDetectionService);
        verify(pushSenderService, never()).send(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void dedup_alreadySentSubscriptionSkippedOthersStillSent() {
        Itinerary itinerary = itineraryWithItems();
        itinerary.setLastKnownTriggerLevel(TriggerLevel.NORMAL);
        itinerary.setLastPeriodicNotifiedAt(null); // 정기 도달
        stubActive(itinerary);
        stubTrigger(TriggerLevel.NORMAL);

        PushSubscription alreadySent = sub("token-already-sent");
        alreadySent.setLastSentKey("2026-08-20:PERIODIC@2026-08-20T10:05");
        PushSubscription fresh = sub("token-fresh");
        stubSubscriptions(alreadySent, fresh);

        scheduler.runTick(NOW);

        verify(pushSenderService, never()).send(eq("token-already-sent"), anyString(), anyString(), anyMap());
        verify(pushSenderService, times(1)).send(eq("token-fresh"), anyString(), anyString(), anyMap());
    }
}
