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

    /**
     * 2026-08-21 - 슬롯 확장(4→최대7) 요구사항 검증. 후보가 서로 가까이 밀집해 있으면 오전2/오후2/
     * 저녁후까지 확장돼 최대 7슬롯(관광5+식사2)에 도달해야 하고, 그 이상은 절대 넘지 않아야 한다.
     */
    @Test
    void denseNearbyAttractions_expandsUpToSevenSlots() {
        List<RecommendationCandidate> attrs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            attrs.add(RecommendationCandidate.builder()
                    .contentId("A" + i)
                    .placeName("관광지" + i)
                    // 모두 반경 0.5km 안쪽 밀집 클러스터 - 어떤 조합이 anchor가 되어도 1.5km 이내
                    .mapX(String.valueOf(127.000 + i * 0.001))
                    .mapY(String.valueOf(37.000 + i * 0.001))
                    .build());
        }
        List<RecommendationCandidate> foods = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            foods.add(RecommendationCandidate.builder()
                    .contentId("F" + i)
                    .placeName("맛집" + i)
                    .mapX(String.valueOf(127.000 + i * 0.001))
                    .mapY(String.valueOf(37.000 + i * 0.001))
                    .build());
        }

        List<RecommendationCandidate> day = service.buildDayRhythm(
                attrs, foods, LocalTime.of(9, 0), false, 0);

        assertEquals(7, day.size(), "밀집 후보가 충분하면 최대 7슬롯까지 확장돼야 함");
        long meals = day.stream().filter(s -> "점심".equals(s.getCategory()) || "저녁".equals(s.getCategory())).count();
        assertEquals(2, meals);
        assertEquals(5, day.size() - meals, "관광 슬롯은 오전1·2 + 오후1·2 + 저녁후 = 5개여야 함");
        assertTrue(day.get(0).getBackupContentId() != null, "대표를 배치할 때 예비 후보도 함께 채워져야 함");
    }

    /** 후보가 서로 멀리 떨어져 있으면(밀도 기준 미충족) 기존과 동일하게 4슬롯(오전1·점심·오후1·저녁)에 머물러야 함 */
    @Test
    void sparseFarApartAttractions_staysAtBaseFourSlots() {
        List<RecommendationCandidate> attrs = new ArrayList<>(List.of(
                RecommendationCandidate.builder().contentId("NEAR").placeName("근처 관광지")
                        .mapX("127.000").mapY("37.000").build(),
                RecommendationCandidate.builder().contentId("FAR1").placeName("먼 관광지1")
                        .mapX("127.100").mapY("37.100").build(),
                RecommendationCandidate.builder().contentId("FAR2").placeName("먼 관광지2")
                        .mapX("127.200").mapY("37.100").build(),
                RecommendationCandidate.builder().contentId("FAR3").placeName("먼 관광지3")
                        .mapX("126.900").mapY("36.900").build()));
        List<RecommendationCandidate> foods = new ArrayList<>(List.of(
                RecommendationCandidate.builder().contentId("F1").placeName("맛집1")
                        .mapX("127.000").mapY("37.000").build(),
                RecommendationCandidate.builder().contentId("F2").placeName("맛집2")
                        .mapX("127.000").mapY("37.000").build()));

        List<RecommendationCandidate> day = service.buildDayRhythm(
                attrs, foods, LocalTime.of(9, 0), false, 0);

        assertEquals(4, day.size(), "밀도 기준을 못 채우면 기존과 동일하게 4슬롯이어야 함");
        long meals = day.stream().filter(s -> "점심".equals(s.getCategory()) || "저녁".equals(s.getCategory())).count();
        assertEquals(2, meals);
        assertEquals(2, day.size() - meals, "오전1·오후1만 배치돼야 함(2번째/저녁후 확장 없음)");
    }
}
