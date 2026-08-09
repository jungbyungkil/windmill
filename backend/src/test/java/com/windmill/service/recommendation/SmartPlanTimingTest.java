package com.windmill.service.recommendation;

import com.windmill.dto.RecommendationCandidate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartPlanTimingTest {

    private final SmartPlanService service = new SmartPlanService(null, null, null, null);

    @Test
    void futureDayStartsAtNine() {
        LocalTime start = service.resolveDayStart(LocalDate.now().plusDays(1));
        assertEquals(LocalTime.of(9, 0), start);
    }

    @Test
    void eveningWindowAllowsFewStops() {
        int max = service.maxStopsForWindow(LocalTime.of(18, 30));
        assertTrue(max >= 1 && max <= 2, "expected 1-2 stops for evening, got " + max);
    }

    @Test
    void tooLateReturnsZero() {
        assertEquals(0, service.maxStopsForWindow(LocalTime.of(20, 30)));
    }

    @Test
    void familyDayIncludesMealsAndFewerSights() {
        List<RecommendationCandidate> attrs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            attrs.add(RecommendationCandidate.builder()
                    .contentId("A" + i)
                    .placeName("관광지" + i)
                    .category("관광")
                    .mapX("128.1")
                    .mapY("37.1")
                    .build());
        }
        List<RecommendationCandidate> foods = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            foods.add(RecommendationCandidate.builder()
                    .contentId("F" + i)
                    .placeName("맛집" + i)
                    .category("식당")
                    .matchedTags(List.of("#맛집"))
                    .mapX("128.2")
                    .mapY("37.2")
                    .build());
        }

        List<RecommendationCandidate> day = service.buildDayRhythm(
                attrs, foods, LocalTime.of(9, 0), true, 0);

        long meals = day.stream().filter(s -> "점심".equals(s.getCategory()) || "저녁".equals(s.getCategory())).count();
        long sights = day.size() - meals;
        assertTrue(meals >= 1, "family day should include at least one meal, got " + meals);
        assertTrue(sights <= 3, "family day should keep sights light, got " + sights);
        assertTrue(day.size() <= 5, "family day should not be overloaded, got " + day.size());
    }
}
