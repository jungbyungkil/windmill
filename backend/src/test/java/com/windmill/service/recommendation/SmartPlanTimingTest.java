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

    /** 2026-08-20 - 혼잡도 "값"으로 완전 재정렬하면 파이프라인이 계산한 개인화 순서가 사라지던 버그 수정 검증 */
    @Test
    void sortComfortablePreservesOrderWithinCrowdBucket() {
        RecommendationCandidate personalized = RecommendationCandidate.builder()
                .contentId("P1").placeName("연령대 맞춤 1순위").crowdRate(40.0).build();
        RecommendationCandidate second = RecommendationCandidate.builder()
                .contentId("P2").placeName("연령대 맞춤 2순위").crowdRate(20.0).build();
        RecommendationCandidate busy = RecommendationCandidate.builder()
                .contentId("P3").placeName("혼잡한 곳").crowdRate(95.0).build();

        // 파이프라인이 이미 개인화 순서로 넘겨준 리스트(personalized가 crowdRate는 더 높아도 먼저 옴)
        List<RecommendationCandidate> result = service.sortComfortable(List.of(personalized, second, busy));

        assertEquals("P1", result.get(0).getContentId(), "혼잡하지 않으면 개인화 순서가 크롤링 값보다 우선해야 함");
        assertEquals("P2", result.get(1).getContentId());
        assertEquals("P3", result.get(2).getContentId(), "혼잡 트리거 걸린 후보만 뒤 버킷으로 밀려야 함");
    }

    @Test
    void takeNearestPicksWithinPersonalizationWindowNotGlobalNearest() {
        RecommendationCandidate origin = RecommendationCandidate.builder()
                .contentId("O").mapX("127.000").mapY("37.000").build();
        List<RecommendationCandidate> pool = new java.util.ArrayList<>();
        // 개인화 윈도우(6개) 안: 약간 멀지만 여기서 골라야 함
        pool.add(RecommendationCandidate.builder().contentId("W1").mapX("127.050").mapY("37.050").build());
        for (int i = 2; i <= 6; i++) {
            pool.add(RecommendationCandidate.builder().contentId("W" + i).mapX("127.090").mapY("37.090").build());
        }
        // 윈도우 밖(7번째): 원점에서 훨씬 가깝지만 개인화 순위가 낮아 윈도우 밖으로 밀려난 후보
        pool.add(RecommendationCandidate.builder().contentId("NEAR_BUT_LOW_RANK").mapX("127.001").mapY("37.001").build());

        RecommendationCandidate chosen = service.takeNearest(pool, origin);

        assertEquals("W1", chosen.getContentId(), "윈도우 밖의 순수 최단거리 후보를 고르면 안 됨");
    }

    @Test
    void takeNearestFallsBackToFullPoolWhenWindowExhausted() {
        RecommendationCandidate origin = RecommendationCandidate.builder()
                .contentId("O").mapX("127.000").mapY("37.000").build();
        // 풀 크기가 윈도우(6)보다 작으면 전체를 스캔 - 정상적으로 최단거리를 고른다
        List<RecommendationCandidate> pool = new java.util.ArrayList<>(List.of(
                RecommendationCandidate.builder().contentId("A").mapX("127.090").mapY("37.090").build(),
                RecommendationCandidate.builder().contentId("B").mapX("127.001").mapY("37.001").build()));

        RecommendationCandidate chosen = service.takeNearest(pool, origin);

        assertEquals("B", chosen.getContentId());
    }
}
