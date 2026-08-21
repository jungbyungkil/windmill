package com.windmill.service.itinerary;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.TourAttractionDetail;
import com.windmill.repository.ItineraryRepository;
import com.windmill.repository.TripRecordRepository;
import com.windmill.service.recommendation.RecommendationPipeline;
import com.windmill.service.region.RegionCodeService;
import com.windmill.service.tourapi.TourAttractionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 삭제 시 자동 대체(요구사항: 슬롯당 예비 후보 2개 중 대표를 지우면 예비로 즉시 채움) 검증.
 * 2026-08-21 - "슬롯 확장 + 삭제 시 자동 대체" 요구사항 세션.
 */
class ItineraryServiceDeleteReplaceTest {

    private static final LocalDate TOMORROW = LocalDate.now().plusDays(1);
    private static final Long ITINERARY_ID = 1L;

    private ItineraryRepository itineraryRepository;
    private TourAttractionService tourAttractionService;
    private RecommendationPipeline recommendationPipeline;
    private ItineraryService service;

    @BeforeEach
    void setUp() {
        itineraryRepository = mock(ItineraryRepository.class);
        TripRecordRepository tripRecordRepository = mock(TripRecordRepository.class);
        RegionCodeService regionCodeService = mock(RegionCodeService.class);
        RouteRecalculationService routeRecalculationService = mock(RouteRecalculationService.class);
        tourAttractionService = mock(TourAttractionService.class);
        recommendationPipeline = mock(RecommendationPipeline.class);
        service = new ItineraryService(itineraryRepository, tripRecordRepository, regionCodeService,
                routeRecalculationService, tourAttractionService, recommendationPipeline);
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static ItineraryItem itemWithBackup(long id, int order, String placeName, String scheduledTime,
                                                 String backupContentId) {
        return ItineraryItem.builder()
                .id(id)
                .displayOrder(order)
                .placeName(placeName)
                .scheduledTime(scheduledTime)
                .visitDate(TOMORROW)
                .backupContentId(backupContentId)
                .backupContentTypeId(12)
                .backupPlaceName("예비장소")
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

    @Test
    void deleteWithValidBackup_autoReplacesFromBackup() {
        itineraryWith(itemWithBackup(1, 0, "경복궁", "10:00", "backup-1"));
        TourAttractionDetail detail = TourAttractionDetail.builder()
                .contentId("backup-1")
                .title("창덕궁")
                .addr1("서울 종로구")
                .mapX("126.99").mapY("37.58")
                .introFields(Map.of("usetime", "09:00~18:00"))
                .build();
        when(tourAttractionService.getDetail("backup-1", 12)).thenReturn(Mono.just(detail));

        ItineraryService.DeleteItemResult result = service.deleteItem(ITINERARY_ID, 1L);

        assertEquals("창덕궁", result.autoReplacedPlaceName());
        assertEquals(1, result.itinerary().getItems().size());
        ItineraryItem replaced = result.itinerary().getItems().get(0);
        assertEquals("창덕궁", replaced.getPlaceName());
        assertEquals("backup-1", replaced.getContentId());
        assertEquals("10:00", replaced.getScheduledTime());
        assertTrue(replaced.isAlternate());
    }

    @Test
    void deleteWithBackupNowClosed_fallsBackToPipelineRefetch() {
        itineraryWith(itemWithBackup(1, 0, "경복궁", "17:30", "backup-1"));
        // 예비 후보가 그 사이 마감시간이 당겨져 17:30 도착 기준으로는 이제 막힘
        TourAttractionDetail detail = TourAttractionDetail.builder()
                .contentId("backup-1")
                .title("창덕궁")
                .introFields(Map.of("usetime", "09:00~18:00")) // 마감 18:00, 버퍼 60분 -> 17:00 이후 불가
                .build();
        when(tourAttractionService.getDetail("backup-1", 12)).thenReturn(Mono.just(detail));
        RecommendationCandidate fallback = RecommendationCandidate.builder()
                .contentId("fallback-1")
                .contentTypeId(12)
                .placeName("경희궁")
                .build();
        when(recommendationPipeline.recommend(any())).thenReturn(Mono.just(List.of(fallback)));

        ItineraryService.DeleteItemResult result = service.deleteItem(ITINERARY_ID, 1L);

        assertEquals("경희궁", result.autoReplacedPlaceName());
        assertEquals("fallback-1", result.itinerary().getItems().get(0).getContentId());
        assertTrue(result.itinerary().getItems().get(0).isAlternate());
    }

    @Test
    void deleteWithoutBackup_fallsBackToPipelineRefetch() {
        ItineraryItem noBackup = ItineraryItem.builder()
                .id(1L).displayOrder(0).placeName("경복궁").scheduledTime("10:00").visitDate(TOMORROW)
                .build();
        itineraryWith(noBackup);
        RecommendationCandidate fallback = RecommendationCandidate.builder()
                .contentId("fallback-1").contentTypeId(12).placeName("경희궁").build();
        when(recommendationPipeline.recommend(any())).thenReturn(Mono.just(List.of(fallback)));

        ItineraryService.DeleteItemResult result = service.deleteItem(ITINERARY_ID, 1L);

        assertEquals("경희궁", result.autoReplacedPlaceName());
    }

    @Test
    void deleteWithNoBackupAndNoFallbackCandidates_leavesSlotEmpty() {
        ItineraryItem noBackup = ItineraryItem.builder()
                .id(1L).displayOrder(0).placeName("경복궁").scheduledTime("10:00").visitDate(TOMORROW)
                .build();
        itineraryWith(noBackup);
        when(recommendationPipeline.recommend(any())).thenReturn(Mono.just(List.of()));

        ItineraryService.DeleteItemResult result = service.deleteItem(ITINERARY_ID, 1L);

        assertNull(result.autoReplacedPlaceName());
        assertTrue(result.itinerary().getItems().isEmpty());
    }
}
