package com.windmill.service.itinerary;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.AddItineraryItemRequest;
import com.windmill.dto.UpdateItineraryItemRequest;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 시간 겹침(TimeConflictGate) 검증 - 사용자 제보: 이미 17시에 다른 일정이 있는데 새 장소를 넣으려
 * 하면 실제 원인(시간 겹침)이 아니라 "이 장소 자체가 마감 임박"이라는 엉뚱한 안내가 뜨던 문제.
 * 겹침은 마감시간 게이트(ClosingTimeGate)와 별개 원인이라 별도 예외(TimeSlotConflictException)로
 * 구분해 던지고, 겹침 검사를 마감시간 검사보다 먼저 한다.
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
        service = new ItineraryService(itineraryRepository, tripRecordRepository, regionCodeService,
                routeRecalculationService, tourAttractionService);
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
     * 2026-08-22 - "일정에 추가" 버튼은 마감·겹침을 이유로 더 이상 거절하지 않는다(사용자 요청:
     * 이미 넣기로 판단하고 누른 액션이니 조건 없이 그대로 반영). updateItem(기존 항목 시간 수정)의
     * 겹침 검사는 그대로 유지됨 - 아래 updateItem_* 테스트 참고.
     */
    @Test
    void addItem_explicitTimeOverlapsExistingItem_addsAnywayWithoutBlocking() {
        itineraryWith(existing(1, 0, "경복궁", "17:00"));

        Itinerary result = service.addItem(ITINERARY_ID, candidate("창덕궁", "17:20", "21:00"));

        assertEquals(2, result.getItems().size());
        ItineraryItem added = result.getItems().stream()
                .filter(i -> "창덕궁".equals(i.getPlaceName())).findFirst().orElseThrow();
        assertEquals("17:20", added.getScheduledTime());
    }

    @Test
    void addItem_bothConflictAndClosingTimeIssues_stillAddsAnyway() {
        // 새 장소 자체는 17:30에 마감(입력 17:20 도착도 마감 임박 버퍼 안에 걸림)이면서 기존 경복궁
        // 17:00과도 겹치는 상황이지만, 두 사유 모두 더 이상 추가 자체를 막지 않는다.
        itineraryWith(existing(1, 0, "경복궁", "17:00"));

        Itinerary result = service.addItem(ITINERARY_ID, candidate("창덕궁", "17:20", "17:30"));

        assertEquals(2, result.getItems().size());
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
    void updateItem_keepingSameTimeAsBefore_doesNotConflictWithItself() {
        itineraryWith(existing(1, 0, "경복궁", "17:00"));

        UpdateItineraryItemRequest request = new UpdateItineraryItemRequest();
        request.setScheduledTime("17:00");

        Itinerary result = service.updateItem(ITINERARY_ID, 1L, request);

        assertEquals("17:00", result.getItems().get(0).getScheduledTime());
    }
}
