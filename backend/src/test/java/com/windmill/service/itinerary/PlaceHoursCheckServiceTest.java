package com.windmill.service.itinerary;

import com.windmill.domain.Itinerary;
import com.windmill.dto.PlaceHoursCheckRequest;
import com.windmill.dto.PlaceHoursCheckResponse;
import com.windmill.dto.TourAttractionDetail;
import com.windmill.repository.ItineraryRepository;
import com.windmill.service.recommendation.KoreanHolidayCache;
import com.windmill.service.tourapi.TourAttractionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlaceHoursCheckServiceTest {

    private static final Long ITINERARY_ID = 1L;
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);

    private ItineraryRepository itineraryRepository;
    private TourAttractionService tourAttractionService;
    private PlaceHoursCheckService service;

    @BeforeEach
    void setUp() {
        KoreanHolidayCache.setForTesting(Map.of());
        itineraryRepository = mock(ItineraryRepository.class);
        tourAttractionService = mock(TourAttractionService.class);
        service = new PlaceHoursCheckService(itineraryRepository, tourAttractionService);

        Itinerary itinerary = Itinerary.builder()
                .id(ITINERARY_ID)
                .startDate(MONDAY)
                .endDate(MONDAY)
                .build();
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Optional.of(itinerary));
    }

    private PlaceHoursCheckRequest request(String restDateText, LocalDate visitDate) {
        PlaceHoursCheckRequest req = new PlaceHoursCheckRequest();
        req.setContentId("c-museum");
        req.setContentTypeId(14);
        req.setPlaceName("서울시립미술관");
        req.setRestDateText(restDateText);
        req.setVisitDate(visitDate);
        return req;
    }

    @Test
    void restDay_warnsButDoesNotNeedDetailFetch() {
        PlaceHoursCheckResponse result = service.check(ITINERARY_ID, request("매주 월요일", MONDAY));

        assertTrue(result.isWarning());
        assertEquals("REST_DAY", result.getWarnings().get(0).getCode());
        assertTrue(result.getWarnings().get(0).getMessage().contains("월요일 휴무"));
        verify(tourAttractionService, never()).getDetail(anyString(), anyInt());
    }

    @Test
    void openWeekday_noWarning() {
        PlaceHoursCheckResponse result = service.check(ITINERARY_ID,
                request("매주 월요일", LocalDate.of(2026, 8, 25)));

        assertFalse(result.isWarning());
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    void holidayShift_usesShiftedClosedDayCopy() {
        KoreanHolidayCache.setForTesting(Map.of(2026, Set.of(LocalDate.of(2026, 8, 17))));
        String rest = "매주 월요일 (단, 월요일이 공휴일인 경우 다음날 휴관)";
        PlaceHoursCheckResponse openMon = service.check(ITINERARY_ID,
                request(rest, LocalDate.of(2026, 8, 17)));
        PlaceHoursCheckResponse shiftedTue = service.check(ITINERARY_ID,
                request(rest, LocalDate.of(2026, 8, 18)));

        assertFalse(openMon.isWarning());
        assertTrue(shiftedTue.isWarning());
        assertEquals("HOLIDAY_SHIFT", shiftedTue.getWarnings().get(0).getCode());
        assertTrue(shiftedTue.getWarnings().get(0).getMessage().contains("휴관"));
    }

    @Test
    void closingSoon_onlyWhenArrivalTimeKnown() {
        PlaceHoursCheckRequest withTime = request(null, MONDAY);
        withTime.setRestDateText("연중무휴");
        withTime.setCloseTime("17:00");
        withTime.setScheduledTime("16:20");

        PlaceHoursCheckRequest withoutTime = request(null, MONDAY);
        withoutTime.setRestDateText("연중무휴");
        withoutTime.setCloseTime("17:00");

        PlaceHoursCheckResponse warned = service.check(ITINERARY_ID, withTime);
        PlaceHoursCheckResponse skipped = service.check(ITINERARY_ID, withoutTime);

        assertTrue(warned.isWarning());
        assertEquals("CLOSING_SOON", warned.getWarnings().get(0).getCode());
        assertFalse(skipped.isWarning());
    }

    @Test
    void missingRestDateText_readsCachedDetail() {
        when(tourAttractionService.peekCachedDetail("c-museum")).thenReturn(
                TourAttractionDetail.builder()
                        .contentId("c-museum")
                        .introFields(Map.of("restdateculture", "매주 월요일"))
                        .build());

        PlaceHoursCheckRequest req = request(null, MONDAY);
        PlaceHoursCheckResponse result = service.check(ITINERARY_ID, req);

        assertTrue(result.isWarning());
        assertEquals("매주 월요일", result.getRestDateText());
        verify(tourAttractionService, never()).getDetail(anyString(), anyInt());
    }

    @Test
    void missingRestDateText_fetchesDetailWhenUncached() {
        when(tourAttractionService.peekCachedDetail("c-museum")).thenReturn(null);
        when(tourAttractionService.getDetail("c-museum", 14)).thenReturn(Mono.just(
                TourAttractionDetail.builder()
                        .contentId("c-museum")
                        .introFields(Map.of("restdate", "매주 월요일"))
                        .build()));

        PlaceHoursCheckResponse result = service.check(ITINERARY_ID, request(null, MONDAY));

        assertTrue(result.isWarning());
        assertEquals("매주 월요일", result.getRestDateText());
    }
}
