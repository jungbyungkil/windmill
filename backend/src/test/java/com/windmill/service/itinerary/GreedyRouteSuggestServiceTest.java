package com.windmill.service.itinerary;

import com.windmill.client.KakaoDirectionsClient;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.SuggestedRouteResponse;
import com.windmill.dto.SuggestedRouteStop;
import com.windmill.util.VisitTiming;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GreedyRouteSuggestServiceTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 31);
    private GreedyRouteSuggestService service;

    @BeforeEach
    void setUp() {
        KakaoDirectionsClient kakao = new KakaoDirectionsClient(WebClient.builder(), "");
        service = new GreedyRouteSuggestService(kakao);
    }

    @Test
    void picksNearestOpenPlaceFirstFromGps() {
        ItineraryItem near = place(1, "가까운카페", "127.001", "37.500", 1);
        ItineraryItem far = place(2, "먼박물관", "127.040", "37.500", 2);
        near.setScheduledTime("15:00");
        far.setScheduledTime("10:00");

        SuggestedRouteResponse result = service.suggest(
                List.of(far, near), 127.0, 37.5,
                LocalDateTime.of(DAY, LocalTime.of(10, 0)));

        assertEquals(List.of(1L, 2L), ids(result.getSuggestedStops()));
        assertTrue(result.isOrderChanged());
        assertEquals("15:00", near.getScheduledTime());
        assertEquals("10:00", far.getScheduledTime());
    }

    @Test
    void closedPlaceIsHardTodayAndAppended() {
        ItineraryItem open = place(1, "열린카페", "127.001", "37.500", 1);
        ItineraryItem closed = place(2, "이미닫힌전시", "127.002", "37.500", 2);
        closed.setCloseTime("12:00");
        closed.setUseTimeText("09:00~12:00");
        open.setUseTimeText("09:00~21:00");
        open.setCloseTime("21:00");

        SuggestedRouteResponse result = service.suggest(
                List.of(closed, open), 127.0, 37.5,
                LocalDateTime.of(DAY, LocalTime.of(14, 0)));

        assertEquals(1, result.getHardTodayCount());
        SuggestedRouteStop last = result.getSuggestedStops().get(result.getSuggestedStops().size() - 1);
        assertEquals(2L, last.getItemId());
        assertTrue(last.isVisitHardToday());
        assertEquals("CLOSING", last.getHardTodayReason());
        assertFalse(result.getSuggestedStops().get(0).isVisitHardToday());
        assertEquals("09:00~12:00", closed.getUseTimeText());
    }

    @Test
    void restDayPlaceIsHardToday() {
        ItineraryItem open = place(1, "열린카페", "127.001", "37.500", 1);
        ItineraryItem rest = place(2, "월요일휴무", "127.002", "37.500", 2);
        rest.setRestDateText("매주 월요일");
        open.setUseTimeText("09:00~21:00");
        rest.setUseTimeText("09:00~18:00");

        SuggestedRouteResponse result = service.suggest(
                List.of(open, rest), 127.0, 37.5,
                LocalDateTime.of(DAY, LocalTime.of(11, 0)));

        assertEquals(1, result.getHardTodayCount());
        SuggestedRouteStop hard = result.getSuggestedStops().stream()
                .filter(SuggestedRouteStop::isVisitHardToday)
                .findFirst()
                .orElseThrow();
        assertEquals(2L, hard.getItemId());
        assertEquals("REST_DAY", hard.getHardTodayReason());
    }

    @Test
    void mealInLunchWindowBeatsSlightlyCloserAttraction() {
        ItineraryItem attraction = place(1, "농업박물관", "127.001", "37.500", 1);
        ItineraryItem meal = place(2, "한식당", "127.003", "37.500", 2);
        meal.setContentTypeId(39);
        meal.setTags(List.of("#맛집"));
        meal.setCategory("점심");
        attraction.setUseTimeText("09:00~18:00");
        meal.setUseTimeText("11:00~21:00");
        meal.setCloseTime("21:00");

        SuggestedRouteResponse result = service.suggest(
                List.of(attraction, meal), 127.0, 37.5,
                LocalDateTime.of(DAY, LocalTime.of(11, 30)));

        assertEquals(2L, result.getSuggestedStops().get(0).getItemId());
        LocalTime first = LocalTime.parse(result.getSuggestedStops().get(0).getScheduledTime());
        assertTrue(VisitTiming.inLunchWindow(first), first.toString());
    }

    @Test
    void waitsForLateOpenInsteadOfMarkingHard() {
        ItineraryItem cafe = place(1, "지금열린카페", "127.001", "37.500", 1);
        ItineraryItem museum = place(2, "오후개방전시", "127.002", "37.500", 2);
        cafe.setUseTimeText("09:00~21:00");
        cafe.setCloseTime("21:00");
        museum.setUseTimeText("14:00~18:00");
        museum.setCloseTime("18:00");

        SuggestedRouteResponse result = service.suggest(
                List.of(museum, cafe), 127.0, 37.5,
                LocalDateTime.of(DAY, LocalTime.of(10, 0)));

        assertEquals(0, result.getHardTodayCount());
        assertEquals(1L, result.getSuggestedStops().get(0).getItemId());
        assertEquals(2L, result.getSuggestedStops().get(1).getItemId());
        assertEquals("14:00", result.getSuggestedStops().get(1).getScheduledTime());
    }

    @Test
    void doesNotMutateOriginalScheduledTimes() {
        ItineraryItem a = place(1, "A", "127.001", "37.500", 1);
        ItineraryItem b = place(2, "B", "127.020", "37.500", 2);
        a.setScheduledTime("09:00");
        b.setScheduledTime("12:00");

        SuggestedRouteResponse result = service.suggest(
                List.of(a, b), 127.0, 37.5,
                LocalDateTime.of(DAY, LocalTime.of(13, 0)));

        assertEquals("09:00", a.getScheduledTime());
        assertEquals("12:00", b.getScheduledTime());
        assertNotEquals("09:00", result.getSuggestedStops().get(0).getScheduledTime());
    }

    @Test
    void mealNudgeUsesSharedLunchWindow() {
        LocalTime nudged = GreedyRouteSuggestService.nudgeMeal(
                LocalTime.of(10, 20), true, false, false);
        assertEquals(VisitTiming.LUNCH_WINDOW_START, nudged);
    }

    private static ItineraryItem place(long id, String name, String lon, String lat, int order) {
        return ItineraryItem.builder()
                .id(id)
                .placeName(name)
                .mapX(lon)
                .mapY(lat)
                .displayOrder(order)
                .visitDate(DAY)
                .contentTypeId(12)
                .build();
    }

    private static List<Long> ids(List<SuggestedRouteStop> stops) {
        return stops.stream().map(SuggestedRouteStop::getItemId).toList();
    }
}
