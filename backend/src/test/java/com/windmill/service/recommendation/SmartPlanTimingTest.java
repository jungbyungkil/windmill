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

    private final SmartPlanService service = new SmartPlanService(null, null, null, null, null);

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
    void familyDayKeepsSightsLightWithoutMeals() {
        List<RecommendationCandidate> attrs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            attrs.add(RecommendationCandidate.builder()
                    .contentId("A" + i)
                    .placeName("관광지" + i)
                    .category("관광")
                    .contentTypeId(12)
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
                    .contentTypeId(39)
                    .matchedTags(List.of("#맛집"))
                    .mapX("128.2")
                    .mapY("37.2")
                    .build());
        }

        List<RecommendationCandidate> day = service.buildDayRhythm(
                attrs, foods, LocalTime.of(9, 0), true, 0);

        long meals = day.stream().filter(s -> "점심".equals(s.getCategory()) || "저녁".equals(s.getCategory())).count();
        assertEquals(0, meals, "스마트 동선은 식당을 자동으로 넣지 않는다");
        assertTrue(day.size() <= 3, "family day should keep sights light, got " + day.size());
        assertTrue(day.stream().noneMatch(s -> Integer.valueOf(39).equals(s.getContentTypeId())));
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
     * 밀집 후보면 오전 2 · 오후 2 관광 슬롯.
     */
    @Test
    void denseNearbyAttractions_fillsMorningAndAfternoonSights() {
        List<RecommendationCandidate> attrs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            attrs.add(RecommendationCandidate.builder()
                    .contentId("A" + i)
                    .placeName("관광지" + i)
                    .category("관광")
                    .contentTypeId(12)
                    .mapX(String.valueOf(127.000 + i * 0.001))
                    .mapY(String.valueOf(37.000 + i * 0.001))
                    .build());
        }
        List<RecommendationCandidate> foods = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            foods.add(RecommendationCandidate.builder()
                    .contentId("F" + i)
                    .placeName("맛집" + i)
                    .contentTypeId(39)
                    .mapX(String.valueOf(127.000 + i * 0.001))
                    .mapY(String.valueOf(37.000 + i * 0.001))
                    .build());
        }

        List<RecommendationCandidate> day = service.buildDayRhythm(
                attrs, foods, LocalTime.of(9, 0), false, 0);

        assertTrue(day.size() >= 3 && day.size() <= 4, "오전·오후 관광 슬롯이어야 함, got " + day.size());
        long meals = day.stream().filter(s -> "점심".equals(s.getCategory()) || "저녁".equals(s.getCategory())).count();
        assertEquals(0, meals);
        assertTrue(day.get(0).getBackupContentId() != null, "대표를 배치할 때 예비 후보도 함께 채워져야 함");
    }

    @Test
    void sparseFarApartAttractions_stillFillsSightSlotsWithoutMeals() {
        List<RecommendationCandidate> attrs = new ArrayList<>(List.of(
                RecommendationCandidate.builder().contentId("NEAR").placeName("근처 관광지")
                        .category("관광").contentTypeId(12)
                        .mapX("127.000").mapY("37.000").build(),
                RecommendationCandidate.builder().contentId("DISTANT1").placeName("먼 관광지1")
                        .category("관광").contentTypeId(12)
                        .mapX("127.100").mapY("37.100").build(),
                RecommendationCandidate.builder().contentId("DISTANT2").placeName("먼 관광지2")
                        .category("관광").contentTypeId(12)
                        .mapX("127.200").mapY("37.100").build(),
                RecommendationCandidate.builder().contentId("DISTANT3").placeName("먼 관광지3")
                        .category("관광").contentTypeId(12)
                        .mapX("126.900").mapY("36.900").build()));
        List<RecommendationCandidate> foods = new ArrayList<>(List.of(
                RecommendationCandidate.builder().contentId("F1").placeName("맛집1")
                        .contentTypeId(39).mapX("127.000").mapY("37.000").build(),
                RecommendationCandidate.builder().contentId("F2").placeName("맛집2")
                        .contentTypeId(39).mapX("127.000").mapY("37.000").build()));

        List<RecommendationCandidate> day = service.buildDayRhythm(
                attrs, foods, LocalTime.of(9, 0), false, 0);

        assertTrue(day.size() >= 2, "관광 슬롯은 채워야 함");
        long meals = day.stream().filter(s -> "점심".equals(s.getCategory()) || "저녁".equals(s.getCategory())).count();
        assertEquals(0, meals);
        assertTrue(day.stream().noneMatch(s -> "F1".equals(s.getContentId()) || "F2".equals(s.getContentId())),
                "식당(F1/F2)이 관광 슬롯에 섞이면 안 됨");
    }

    @Test
    void standardDaySlots_fillsMorningAndAfternoonSightsWithoutMeals() {
        List<RecommendationCandidate> attrs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            attrs.add(RecommendationCandidate.builder()
                    .contentId("A" + i)
                    .placeName("관광지" + i)
                    .category("관광")
                    .contentTypeId(12)
                    .mapX("127.00" + i)
                    .mapY("37.00" + i)
                    .build());
        }

        List<RecommendationCandidate> day = service.assembleStandardDaySlots(attrs);

        assertTrue(day.size() >= 3 && day.size() <= 4, "오전·오후 관광이어야 함, got " + day.size());
        long meals = day.stream().filter(s -> "점심".equals(s.getCategory()) || "저녁".equals(s.getCategory())).count();
        long cafeCount = day.stream().filter(s -> "카페".equals(s.getCategory())).count();
        assertEquals(0, meals);
        assertEquals(0, cafeCount);
        // 슬롯의 시간대(오전/오후)는 category가 아니라 suggestedTime/oneLiner에 담긴다 -
        // category에는 장소 본래 유형("관광" 등)을 그대로 둔다(skipsRestaurantsAndClosedDays 참고).
        assertTrue(day.get(0).getSuggestedTime().compareTo("12:00") < 0, "첫 슬롯은 오전");
        assertTrue(day.stream().anyMatch(s -> s.getSuggestedTime() != null
                && s.getSuggestedTime().compareTo("12:00") >= 0), "오후 슬롯이 있어야 함");
    }

    @Test
    void standardDaySlots_skipsRestaurantsAndClosedDays() {
        List<RecommendationCandidate> attrs = new ArrayList<>(List.of(
                RecommendationCandidate.builder().contentId("TOP1").placeName("인기1")
                        .category("관광").contentTypeId(12).rank(1).mapX("127.000").mapY("37.000").build(),
                RecommendationCandidate.builder().contentId("CLOSED").placeName("월요일휴무관")
                        .category("관광").contentTypeId(14).rank(2).restDateText("매주 월요일")
                        .mapX("127.001").mapY("37.001").build(),
                RecommendationCandidate.builder().contentId("FOOD").placeName("맛집")
                        .category("식당").contentTypeId(39).rank(3).mapX("127.002").mapY("37.002").build(),
                RecommendationCandidate.builder().contentId("TOP2").placeName("인기2")
                        .category("관광").contentTypeId(12).rank(4).mapX("127.003").mapY("37.003").build()));

        LocalDate monday = LocalDate.of(2026, 9, 7);
        List<RecommendationCandidate> day = service.assembleStandardDaySlots(attrs, monday);

        assertTrue(day.stream().noneMatch(s -> "FOOD".equals(s.getContentId())));
        assertTrue(day.stream().noneMatch(s -> "CLOSED".equals(s.getContentId())));
        assertEquals("TOP1", day.get(0).getContentId());
    }

    @Test
    void standardDaySlots_skipsPlaceThatClosesBeforeAfternoonSlot() {
        List<RecommendationCandidate> attrs = new ArrayList<>(List.of(
                RecommendationCandidate.builder().contentId("MORNING").placeName("오전가능")
                        .category("관광").contentTypeId(12).rank(1)
                        .closeTime("18:00").mapX("127.000").mapY("37.000").build(),
                RecommendationCandidate.builder().contentId("EARLY_CLOSE").placeName("낮에마감")
                        .category("관광").contentTypeId(12).rank(2)
                        .closeTime("12:00").mapX("127.001").mapY("37.001").build(),
                RecommendationCandidate.builder().contentId("AFTER").placeName("오후가능")
                        .category("관광").contentTypeId(12).rank(3)
                        .closeTime("18:00").mapX("127.002").mapY("37.002").build(),
                RecommendationCandidate.builder().contentId("AFTER2").placeName("오후가능2")
                        .category("관광").contentTypeId(12).rank(4)
                        .closeTime("18:00").mapX("127.003").mapY("37.003").build()));

        LocalDate sunday = LocalDate.of(2026, 9, 6);
        List<RecommendationCandidate> day = service.assembleStandardDaySlots(attrs, sunday);

        assertTrue(day.stream().noneMatch(s -> "EARLY_CLOSE".equals(s.getContentId())
                && ("14:00".equals(s.getSuggestedTime()) || "16:00".equals(s.getSuggestedTime()))));
    }

    @Test
    void festivalGoesToMorning() {
        List<RecommendationCandidate> attrs = new ArrayList<>(List.of(
                RecommendationCandidate.builder().contentId("SPOT").placeName("인기명소")
                        .category("관광").contentTypeId(12).rank(1).crowdRate(40.0)
                        .mapX("127.000").mapY("37.000").build(),
                RecommendationCandidate.builder().contentId("FEST").placeName("지역축제")
                        .category("축제").contentTypeId(15).rank(9).crowdRate(10.0)
                        .mapX("127.001").mapY("37.001").build()));

        List<RecommendationCandidate> day = service.assembleStandardDaySlots(attrs);

        assertEquals("FEST", day.get(0).getContentId(), "그날 축제는 오전에 가야 함");
    }

    @Test
    void sortPopular_putsHighCrowdFirst() {
        RecommendationCandidate quiet = RecommendationCandidate.builder()
                .contentId("Q").placeName("한산한 곳").crowdRate(20.0).build();
        RecommendationCandidate famous = RecommendationCandidate.builder()
                .contentId("F").placeName("인기 명소").crowdRate(95.0).build();
        RecommendationCandidate unknown = RecommendationCandidate.builder()
                .contentId("U").placeName("집계 없음").build();

        List<RecommendationCandidate> result = service.sortPopular(List.of(quiet, unknown, famous));

        assertEquals("F", result.get(0).getContentId(), "집중률이 높은 인기 명소가 맨 앞이어야 함");
        assertEquals("Q", result.get(1).getContentId());
        assertEquals("U", result.get(2).getContentId(), "집계 없는 후보는 맨 뒤");
    }

    @Test
    void sortPopular_tourApiRankComesBeforeCrowdRate() {
        RecommendationCandidate busyLowRank = RecommendationCandidate.builder()
                .contentId("BUSY").placeName("조회 낮은 혼잡 명소").rank(9).crowdRate(95.0).build();
        RecommendationCandidate topQuiet = RecommendationCandidate.builder()
                .contentId("TOP").placeName("지역 1위").rank(1).crowdRate(22.0).build();

        List<RecommendationCandidate> result = service.sortPopular(List.of(busyLowRank, topQuiet));

        assertEquals("TOP", result.get(0).getContentId(), "TourAPI 조회순이 집중률보다 우선해야 함");
        assertEquals("BUSY", result.get(1).getContentId());
    }

    @Test
    void peekMostPopular_picksRegionalNumberOneOutsideWindow() {
        List<RecommendationCandidate> pool = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            pool.add(RecommendationCandidate.builder()
                    .contentId("W" + i).rank(i + 1).crowdRate(30.0).build());
        }
        pool.add(RecommendationCandidate.builder()
                .contentId("REGION_TOP").rank(1).crowdRate(40.0).build());

        RecommendationCandidate chosen = service.peekMostPopular(pool);

        assertEquals("REGION_TOP", chosen.getContentId(), "개인화 윈도우 밖의 지역 1위도 골라야 함");
    }

    @Test
    void crowdedPopularAttraction_goesToMorningEvenIfFarther() {
        List<RecommendationCandidate> attrs = new ArrayList<>(List.of(
                RecommendationCandidate.builder().contentId("QUIET_NEAR").placeName("가까운 한산한 곳")
                        .category("관광").contentTypeId(12)
                        .crowdRate(18.0).mapX("127.000").mapY("37.000").build(),
                RecommendationCandidate.builder().contentId("FAMOUS_FAR").placeName("먼 인기 명소")
                        .category("관광").contentTypeId(12)
                        .crowdRate(92.0).mapX("127.080").mapY("37.080").build(),
                RecommendationCandidate.builder().contentId("FAMOUS_NEAR").placeName("가까운 인기 명소")
                        .category("관광").contentTypeId(12)
                        .crowdRate(78.0).mapX("127.002").mapY("37.002").build(),
                RecommendationCandidate.builder().contentId("MID").placeName("중간 관광지")
                        .category("관광").contentTypeId(12)
                        .crowdRate(40.0).mapX("127.010").mapY("37.010").build()));

        List<RecommendationCandidate> day = service.assembleStandardDaySlots(attrs);

        assertEquals("FAMOUS_FAR", day.get(0).getContentId(), "가장 인기(혼잡)한 곳은 오전에 가야 함");
        assertEquals("09:00", day.get(0).getSuggestedTime());
        assertTrue(day.stream().anyMatch(s -> "14:00".equals(s.getSuggestedTime())), "오후 슬롯이 있어야 함");
        assertTrue(day.get(0).getOneLiner().contains("오전에"), "붐비는 인기 명소는 오전 안내가 있어야 함");
        assertTrue(day.stream().noneMatch(s -> "점심".equals(s.getCategory())));
    }
}
