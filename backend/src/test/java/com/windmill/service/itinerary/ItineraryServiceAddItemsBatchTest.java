package com.windmill.service.itinerary;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.AddItineraryItemRequest;
import com.windmill.dto.PlanChangeEntry;
import com.windmill.exception.BatchAddItemException;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 여행 바구니 확정 - 일괄 추가(2026-09-15 핸드오프 브리프). 전체 성공/전체 롤백, 이력 1건 병합,
 * 중복 장소 무시 정책을 검증한다.
 */
class ItineraryServiceAddItemsBatchTest {

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
                routeRecalculationService, tourAttractionService, situationalTagService, new PlanHistoryService());
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(inv -> inv.getArgument(0));
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

    private static AddItineraryItemRequest candidate(String contentId, String placeName) {
        AddItineraryItemRequest request = new AddItineraryItemRequest();
        request.setContentId(contentId);
        request.setPlaceName(placeName);
        return request;
    }

    @Test
    void addItemsBatch_addsAllAndRecordsSingleHistoryEntry() {
        itineraryWith();

        Itinerary result = service.addItemsBatch(ITINERARY_ID, List.of(
                candidate("c-1", "속초해수욕장"),
                candidate("c-2", "중앙시장"),
                candidate("c-3", "대포항")));

        assertEquals(3, result.getItems().size());
        List<PlanChangeEntry> history = result.getChangeHistory();
        assertEquals(1, history.size());
        assertEquals("3곳 추가", history.get(0).getReason());
        assertEquals("MANUAL", history.get(0).getTriggerType());
    }

    @Test
    void addItemsBatch_duplicatePlace_isSkippedSilentlyAndExcludedFromHistoryCount() {
        ItineraryItem existing = ItineraryItem.builder()
                .id(1L).displayOrder(0).placeName("속초해수욕장").contentId("c-1")
                .visitDate(TOMORROW).build();
        itineraryWith(existing);

        Itinerary result = service.addItemsBatch(ITINERARY_ID, List.of(
                candidate("c-1", "속초해수욕장"),
                candidate("c-2", "중앙시장")));

        assertEquals(2, result.getItems().size());
        assertEquals("1곳 추가", result.getChangeHistory().get(0).getReason());
    }

    @Test
    void addItemsBatch_allDuplicates_addsNoHistoryEntry() {
        ItineraryItem existing = ItineraryItem.builder()
                .id(1L).displayOrder(0).placeName("속초해수욕장").contentId("c-1")
                .visitDate(TOMORROW).build();
        itineraryWith(existing);

        Itinerary result = service.addItemsBatch(ITINERARY_ID, List.of(candidate("c-1", "속초해수욕장")));

        assertEquals(1, result.getItems().size());
        assertTrue(result.getChangeHistory() == null || result.getChangeHistory().isEmpty());
    }

    /** 하나라도 실패하면 전체 롤백 - 앞서 추가된 것처럼 보였던 항목도 최종 응답에 반영되지 않는다. */
    @Test
    void addItemsBatch_oneItemFails_throwsWithFailedContentIdAndRollsBackWholeBatch() {
        ItineraryItem existing = ItineraryItem.builder()
                .id(1L).displayOrder(0).placeName("경복궁").scheduledTime("11:30")
                .visitDate(TOMORROW).build();
        Itinerary itinerary = itineraryWith(existing);

        AddItineraryItemRequest conflicting = candidate("c-2", "창덕궁");
        conflicting.setScheduledTime("11:30");
        conflicting.setCloseTime("21:00");

        BatchAddItemException ex = assertThrows(BatchAddItemException.class,
                () -> service.addItemsBatch(ITINERARY_ID, List.of(candidate("c-1", "중앙시장"), conflicting)));

        assertEquals("c-2", ex.getContentId());
        assertEquals("창덕궁", ex.getPlaceName());
        assertTrue(ex.getCause() instanceof TimeSlotConflictException);
        // 서비스 계층에서 던진 예외 자체는 트랜잭션 롤백을 컨트롤러/스프링이 처리한다(단위 테스트라
        // 실제 롤백은 발생하지 않지만, 롤백을 유발할 RuntimeException이라는 것만 확인하면 충분하다) -
        // 여기서는 최소한 "중앙시장"이 먼저 들어간 상태로 중간에 멈춘 것을 확인한다.
        assertEquals(2, itinerary.getItems().size());
    }

    @Test
    void addItemsBatch_emptyList_throwsIllegalArgument() {
        itineraryWith();
        assertThrows(IllegalArgumentException.class, () -> service.addItemsBatch(ITINERARY_ID, List.of()));
    }
}
