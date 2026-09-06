package com.windmill.service.itinerary;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.AddItineraryItemRequest;
import com.windmill.dto.UpdateItineraryItemRequest;
import com.windmill.exception.ClosingTimeInfeasibleException;
import com.windmill.exception.TimeSlotConflictException;
import com.windmill.repository.ItineraryRepository;
import com.windmill.repository.TripRecordRepository;
import com.windmill.service.region.RegionCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 시간 겹침(TimeConflictGate) 검증 - 사용자 제보: 이미 17시에 다른 일정이 있는데 새 장소를 넣으려
 * 하면 실제 원인(시간 겹침)이 아니라 "이 장소 자체가 마감 임박"이라는 엉뚱한 안내가 뜨던 문제.
 * 겹침은 마감시간 게이트(ClosingTimeGate)와 별개 원인이라 별도 예외(TimeSlotConflictException)로
 * 구분해 던지고, 겹침 검사를 마감시간 검사보다 먼저 한다. 명시 시각 추가는 저장을 막는다.
 */
class ItineraryServiceTimeConflictTest {

    private static final LocalDate TOMORROW = LocalDate.now().plusDays(1);
    private static final Long ITINERARY_ID = 1L;

    private ItineraryRepository itineraryRepository;
    private ItineraryService service;

    @BeforeEach
    void setUp() {
        itineraryRepository = mock(ItineraryRepository.class);
        TripRecordRepository tripRecordRepository = mock(TripRecordRepository.class);
        RegionCodeService regionCodeService = mock(RegionCodeService.class);
        RouteRecalculationService routeRecalculationService = mock(RouteRecalculationService.class);
        com.windmill.service.tourapi.TourAttractionService tourAttractionService =
                mock(com.windmill.service.tourapi.TourAttractionService.class);
        com.windmill.service.recommendation.SituationalTagService situationalTagService =
                mock(com.windmill.service.recommendation.SituationalTagService.class);
        when(situationalTagService.ensureInferred(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> com.windmill.domain.PlaceSituationalTags.builder()
                        .contentId(inv.getArgument(0))
                        .indoorYn(false)
                        .rainSensitivity(com.windmill.domain.RainSensitivity.SENSITIVE)
                        .congestionSensitivity(com.windmill.domain.CongestionSensitivity.SENSITIVE)
                        .inferredSource(com.windmill.domain.InferredSource.RULE)
                        .updatedAt(java.time.LocalDateTime.now())
                        .build());
        service = new ItineraryService(itineraryRepository, tripRecordRepository, regionCodeService,
                routeRecalculationService, tourAttractionService, situationalTagService);
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static ItineraryItem existing(long id, int order, String placeName, String scheduledTime) {
        return ItineraryItem.builder()
                .id(id)
                .displayOrder(order)
                .placeName(placeName)
                .scheduledTime(scheduledTime)
                .visitDate(TOMORROW)
                .build();
    }

    private Itinerary itineraryWith(ItineraryItem... items) {
        Itinerary itinerary = Itinerary.builder()
                .id(ITINERARY_ID)
                .sessionUuid("session-uuid")
                .startDate(TOMORROW)
                .endDate(TOMORROW)
                .build();
        for (ItineraryItem item : items) {
            item.setItinerary(itinerary);
            itinerary.getItems().add(item);
        }
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Optional.of(itinerary));
        return itinerary;
    }

    private static AddItineraryItemRequest candidate(String placeName, String scheduledTime, String closeTime) {
        AddItineraryItemRequest request = new AddItineraryItemRequest();
        request.setContentId("c-" + placeName);
        request.setPlaceName(placeName);
        request.setScheduledTime(scheduledTime);
        request.setCloseTime(closeTime);
        return request;
    }

    /**
     * 명시 시각이 기존 슬롯과 겹치면 저장을 막고 TIME_OVERLAP + 대안 시각을 돌려준다.
     */
    @Test
    void addItem_explicitTimeOverlapsExistingItem_throwsTimeSlotConflictWithSuggestions() {
        itineraryWith(existing(1, 0, "세종뮤지엄갤러리", "11:30"));

        TimeSlotConflictException ex = assertThrows(TimeSlotConflictException.class,
                () -> service.addItem(ITINERARY_ID, candidate("호재래", "11:30", "21:00")));

        assertEquals(1L, ex.getConflictingItemId());
        assertEquals("세종뮤지엄갤러리", ex.getConflictingPlaceName());
        assertFalse(ex.getSuggestedTimes().isEmpty());
        assertTrue(ex.getMessage().contains("세종뮤지엄갤러리"));
    }

    @Test
    void addItem_bothConflictAndClosingTimeIssues_reportsOverlapFirst() {
        // 새 장소 자체는 17:30에 마감이면서 기존 경복궁 17:00과도 겹친다 — 겹침이 우선.
        itineraryWith(existing(1, 0, "경복궁", "17:00"));

        TimeSlotConflictException ex = assertThrows(TimeSlotConflictException.class,
                () -> service.addItem(ITINERARY_ID, candidate("창덕궁", "17:20", "17:30")));

        assertEquals("경복궁", ex.getConflictingPlaceName());
    }

    @Test
    void addItem_explicitTimeAfterClose_throwsClosingTimeInfeasible() {
        itineraryWith(existing(1, 0, "경복궁", "10:00"));

        ClosingTimeInfeasibleException ex = assertThrows(ClosingTimeInfeasibleException.class,
                () -> service.addItem(ITINERARY_ID, candidate("우표박물관", "16:20", "16:50")));

        assertEquals(java.time.LocalTime.of(16, 50), ex.getCloseTime());
        assertFalse(ex.getSuggestedTimes().isEmpty());
    }

    @Test
    void addItem_acknowledgeHoursWarning_allowsAfterClose() {
        itineraryWith(existing(1, 0, "경복궁", "10:00"));

        AddItineraryItemRequest request = candidate("우표박물관", "16:20", "16:50");
        request.setAcknowledgeHoursWarning(true);

        Itinerary result = service.addItem(ITINERARY_ID, request);

        assertTrue(result.getItems().stream().anyMatch(i -> "우표박물관".equals(i.getPlaceName())));
    }

    @Test
    void addItem_acknowledgeHoursWarning_stillBlocksTimeOverlap() {
        itineraryWith(existing(1, 0, "세종뮤지엄갤러리", "11:30"));

        AddItineraryItemRequest request = candidate("호재래", "11:30", "21:00");
        request.setAcknowledgeHoursWarning(true);

        assertThrows(TimeSlotConflictException.class, () -> service.addItem(ITINERARY_ID, request));
    }

    @Test
    void addItem_cafeThirtyMinutesAfterAttraction_isAllowed() {
        itineraryWith(existing(1, 0, "경복궁", "11:30"));

        AddItineraryItemRequest cafe = candidate("스타벅스 광화문점", "12:00", "21:00");
        cafe.setCategory("카페");
        cafe.setTags(List.of("#카페"));

        Itinerary result = service.addItem(ITINERARY_ID, cafe);

        assertEquals(2, result.getItems().size());
        assertTrue(result.getItems().stream().anyMatch(i -> "스타벅스 광화문점".equals(i.getPlaceName())));
    }

    @Test
    void addItem_explicitTimeDoesNotOverlap_succeeds() {
        itineraryWith(existing(1, 0, "경복궁", "17:00"));

        Itinerary result = service.addItem(ITINERARY_ID, candidate("창덕궁", "19:00", "21:00"));

        List<ItineraryItem> byOrder = result.getItems().stream()
                .sorted((a, b) -> Integer.compare(a.getDisplayOrder(), b.getDisplayOrder()))
                .toList();
        assertEquals(2, byOrder.size());
        assertEquals("창덕궁", byOrder.get(1).getPlaceName());
        assertEquals("19:00", byOrder.get(1).getScheduledTime());
    }

    @Test
    void updateItem_movingTimeIntoAnotherItemsSlot_throwsTimeSlotConflict() {
        Itinerary itinerary = itineraryWith(
                existing(1, 0, "경복궁", "17:00"),
                existing(2, 1, "창덕궁", "10:00"));

        UpdateItineraryItemRequest request = new UpdateItineraryItemRequest();
        request.setScheduledTime("17:10");

        TimeSlotConflictException ex = assertThrows(TimeSlotConflictException.class,
                () -> service.updateItem(ITINERARY_ID, 2L, request));

        assertEquals(1L, ex.getConflictingItemId());
        assertEquals("경복궁", ex.getConflictingPlaceName());
        // 원래 시각은 그대로 남아있어야 한다(부분 반영 없이 전체 실패)
        assertEquals("10:00", itinerary.getItems().stream()
                .filter(i -> i.getId() == 2L).findFirst().orElseThrow().getScheduledTime());
    }

    @Test
    void updateItem_afternoonTimeAfterMorningLastStop_isAllowed() {
        itineraryWith(
                existing(1, 0, "아쿠아플라넷 제주", "09:00"),
                existing(2, 1, "고흐의정원", "10:10"),
                existing(3, 2, "가시식당", "11:05"));

        UpdateItineraryItemRequest request = new UpdateItineraryItemRequest();
        request.setScheduledTime("14:00");

        Itinerary result = service.updateItem(ITINERARY_ID, 2L, request);

        assertEquals("14:00", result.getItems().stream()
                .filter(i -> i.getId() == 2L).findFirst().orElseThrow().getScheduledTime());
    }

    @Test
    void updateItem_keepingSameTimeAsBefore_doesNotConflictWithItself() {
        itineraryWith(existing(1, 0, "경복궁", "17:00"));

        UpdateItineraryItemRequest request = new UpdateItineraryItemRequest();
        request.setScheduledTime("17:00");

        Itinerary result = service.updateItem(ITINERARY_ID, 1L, request);

        assertEquals("17:00", result.getItems().get(0).getScheduledTime());
    }

    @Test
    void addItem_sameContentIdSameDay_isIdempotent() {
        ItineraryItem ddp = existing(1, 0, "동대문디자인플라자", "14:00");
        ddp.setContentId("ddp-1");
        itineraryWith(ddp);

        AddItineraryItemRequest request = candidate("DDP", null, null);
        request.setContentId("ddp-1");

        Itinerary result = service.addItem(ITINERARY_ID, request);

        assertEquals(1, result.getItems().size());
        assertEquals("동대문디자인플라자", result.getItems().get(0).getPlaceName());
    }
}
