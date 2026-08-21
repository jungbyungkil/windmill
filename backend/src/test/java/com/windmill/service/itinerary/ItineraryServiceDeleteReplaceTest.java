package com.windmill.service.itinerary;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.TourAttractionDetail;
import com.windmill.repository.ItineraryRepository;
import com.windmill.repository.TripRecordRepository;
import com.windmill.service.region.RegionCodeService;
import com.windmill.service.tourapi.TourAttractionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
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
 * 2026-08-21 - "슬롯 확장 + 삭제 시 자동 대체" 요구사항 세션. 최초 구현엔 예비 후보가 없거나
 * 무효할 때 파이프라인 재조회 폴백이 있었으나, 삭제 자체가 느려지거나 실패하는 회귀를 일으켜
 * 제거함(사용자 제보 - "삭제 버튼이 안 먹는다") - 이제 예비 후보가 없으면 그냥 빈 자리로 둔다.
 */
class ItineraryServiceDeleteReplaceTest {

    private static final LocalDate TOMORROW = LocalDate.now().plusDays(1);
    private static final Long ITINERARY_ID = 1L;

    private ItineraryRepository itineraryRepository;
    private TourAttractionService tourAttractionService;
    private ItineraryService service;

    @BeforeEach
    void setUp() {
        itineraryRepository = mock(ItineraryRepository.class);
        TripRecordRepository tripRecordRepository = mock(TripRecordRepository.class);
        RegionCodeService regionCodeService = mock(RegionCodeService.class);
        RouteRecalculationService routeRecalculationService = mock(RouteRecalculationService.class);
        tourAttractionService = mock(TourAttractionService.class);
        service = new ItineraryService(itineraryRepository, tripRecordRepository, regionCodeService,
                routeRecalculationService, tourAttractionService);
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

    /** 예비 후보가 그 사이 마감시간이 당겨져 지금은 무효 - 재조회 폴백 없이 그냥 빈 자리로 남는다 */
    @Test
    void deleteWithBackupNowClosed_leavesSlotEmpty() {
        itineraryWith(itemWithBackup(1, 0, "경복궁", "17:30", "backup-1"));
        TourAttractionDetail detail = TourAttractionDetail.builder()
                .contentId("backup-1")
                .title("창덕궁")
                .introFields(Map.of("usetime", "09:00~18:00")) // 마감 18:00, 버퍼 60분 -> 17:00 이후 불가
                .build();
        when(tourAttractionService.getDetail("backup-1", 12)).thenReturn(Mono.just(detail));

        ItineraryService.DeleteItemResult result = service.deleteItem(ITINERARY_ID, 1L);

        assertNull(result.autoReplacedPlaceName());
        assertTrue(result.itinerary().getItems().isEmpty());
    }

    /** 예비 후보 자체가 없으면(이 기능 배포 전에 담긴 항목 등) 파이프라인을 다시 돌리지 않고 그냥 삭제한다 */
    @Test
    void deleteWithoutBackup_justDeletes_noPipelineCall() {
        ItineraryItem noBackup = ItineraryItem.builder()
                .id(1L).displayOrder(0).placeName("경복궁").scheduledTime("10:00").visitDate(TOMORROW)
                .build();
        itineraryWith(noBackup);

        ItineraryService.DeleteItemResult result = service.deleteItem(ITINERARY_ID, 1L);

        assertNull(result.autoReplacedPlaceName());
        assertTrue(result.itinerary().getItems().isEmpty());
    }
}
