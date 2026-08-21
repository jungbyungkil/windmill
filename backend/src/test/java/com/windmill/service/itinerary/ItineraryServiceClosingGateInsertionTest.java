package com.windmill.service.itinerary;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.AddItineraryItemRequest;
import com.windmill.repository.ItineraryRepository;
import com.windmill.repository.TripRecordRepository;
import com.windmill.service.region.RegionCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 마감시간 때문에 하루 맨 끝에는 못 붙는 장소를 무조건 차단하지 않고, 마감을 넘기지 않는 자리를
 * 찾아 끼워 넣는 로직 검증 (2026-08-16 사용자 제보 - 8/17 예정 미래 일정인데 우표박물관이 이미
 * 저녁까지 꽉 찬 하루 끝에 이어붙이려니 마감을 넘긴 걸로 계산돼 선택 불가로 막히던 문제).
 */
class ItineraryServiceClosingGateInsertionTest {

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
        // mapX/mapY 미설정 - travelMinutes()가 항상 기본 20분을 쓰도록 해 테스트를 결정적으로 만든다.
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

    private static AddItineraryItemRequest candidate(String placeName, String closeTime) {
        AddItineraryItemRequest request = new AddItineraryItemRequest();
        request.setContentId("c-" + placeName);
        request.setPlaceName(placeName);
        request.setCloseTime(closeTime);
        return request;
    }

    @Test
    void blockedAtDayEnd_insertsAtLatestFeasibleSlotInstead() {
        itineraryWith(
                existing(1, 0, "새봄떡국국수", "11:00"),
                existing(2, 1, "농업박물관", "12:20"),
                existing(3, 2, "국도발전전시관", "18:20"));

        Itinerary result = service.addItem(ITINERARY_ID, candidate("우표박물관", "16:50"));

        List<ItineraryItem> byOrder = result.getItems().stream()
                .sorted((a, b) -> Integer.compare(a.getDisplayOrder(), b.getDisplayOrder()))
                .toList();
        assertEquals(4, byOrder.size());
        assertEquals("새봄떡국국수", byOrder.get(0).getPlaceName());
        assertEquals("11:00", byOrder.get(0).getScheduledTime());
        assertEquals("농업박물관", byOrder.get(1).getPlaceName());
        assertEquals("12:20", byOrder.get(1).getScheduledTime());
        // 마감(16:50-60분=15:50) 안에 드는 가장 늦은 자리 - 농업박물관(12:20) 뒤, 국도발전전시관 앞
        assertEquals("우표박물관", byOrder.get(2).getPlaceName());
        assertEquals("13:55", byOrder.get(2).getScheduledTime());
        // 뒤로 밀린 국도발전전시관은 새 위치 기준으로 시각이 재계산된다
        assertEquals("국도발전전시관", byOrder.get(3).getPlaceName());
        assertEquals("15:30", byOrder.get(3).getScheduledTime());
    }

    @Test
    void noFeasibleSlotAnywhere_stillBlocksWithMessage() {
        itineraryWith(
                existing(1, 0, "새봄떡국국수", "11:00"),
                existing(2, 1, "농업박물관", "12:20"),
                existing(3, 2, "국도발전전시관", "18:20"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addItem(ITINERARY_ID, candidate("초조기마감장소", "10:00")));
        assertTrue(ex.getMessage().contains("10시"));
    }

    @Test
    void fitsNaturallyAtDayEnd_appendsWithoutForcingScheduledTime() {
        itineraryWith(
                existing(1, 0, "새봄떡국국수", "11:00"),
                existing(2, 1, "농업박물관", "12:20"),
                existing(3, 2, "국도발전전시관", "18:20"));

        Itinerary result = service.addItem(ITINERARY_ID, candidate("심야카페", "22:00"));

        List<ItineraryItem> byOrder = result.getItems().stream()
                .sorted((a, b) -> Integer.compare(a.getDisplayOrder(), b.getDisplayOrder()))
                .toList();
        assertEquals(4, byOrder.size());
        assertEquals("심야카페", byOrder.get(3).getPlaceName());
        assertNull(byOrder.get(3).getScheduledTime()); // 기존 동작 그대로 - 강제로 시각을 채우지 않는다
        assertEquals("18:20", byOrder.get(2).getScheduledTime()); // 앞선 항목들은 안 건드림
    }

    @Test
    void emptyFutureDay_earlyClosingPlace_stillBlockedByOriginalPath() {
        itineraryWith();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.addItem(ITINERARY_ID, candidate("이른마감장소", "09:30")));
        assertTrue(ex.getMessage().contains("9시"));
    }
}
