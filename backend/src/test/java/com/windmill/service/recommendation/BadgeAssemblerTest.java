package com.windmill.service.recommendation;

import com.windmill.dto.Badge;
import com.windmill.dto.RecommendationCandidate;
import com.windmill.service.trigger.RegionCondition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TOURAPI_KEY가 없는 환경(실제 API 호출 불가)에서도 배지 조립 로직을 검증하기 위한 순수 단위 테스트.
 * 실제 데이터로는 backend2.log의 401 응답 때문에 후보가 0건이라 브라우저로는 이 로직을 눈으로 볼 수 없어,
 * RegionCondition/RecommendationCandidate를 직접 만들어 임계값·상대비교 판정만 따로 확인한다.
 */
class BadgeAssemblerTest {

    private final BadgeAssembler badgeAssembler = new BadgeAssembler();

    @Test
    void weatherBadgeAppearsAbovePopThreshold() {
        RegionCondition condition = RegionCondition.builder()
                .crowdRateByPlaceName(Map.of())
                .currentPop(70.0)
                .currentPopFcstTime("1400")
                .build();
        RecommendationCandidate candidate = RecommendationCandidate.builder().build();

        badgeAssembler.attach(List.of(candidate), condition);

        assertTrue(candidate.getBadges().stream().anyMatch(b -> b.getType() == Badge.BadgeType.WEATHER
                && b.getLabel().equals("비 예보 14시") && b.getSeverity() == Badge.Severity.WARNING));
    }

    @Test
    void noWeatherBadgeBelowThreshold() {
        RegionCondition condition = RegionCondition.builder()
                .crowdRateByPlaceName(Map.of())
                .currentPop(30.0)
                .build();
        RecommendationCandidate candidate = RecommendationCandidate.builder().build();

        badgeAssembler.attach(List.of(candidate), condition);

        assertTrue(candidate.getBadges().stream().noneMatch(b -> b.getType() == Badge.BadgeType.WEATHER));
    }

    @Test
    void congestionBadgeAtAbsoluteThreshold() {
        RegionCondition condition = RegionCondition.builder().crowdRateByPlaceName(Map.of()).build();
        RecommendationCandidate candidate = RecommendationCandidate.builder().crowdRate(95.0).build();

        badgeAssembler.attach(List.of(candidate), condition);

        assertEquals(1, candidate.getBadges().stream().filter(b -> b.getType() == Badge.BadgeType.CONGESTION).count());
        assertEquals("혼잡 예상", candidate.getBadges().get(0).getLabel());
    }

    @Test
    void congestionBadgeWhenRelativelyHigherThanRegionAverage() {
        // 지역 평균 30% - 절대 임계치(90%) 미만이라도 평소보다 15%p 이상 높으면 배지 표시 (기능5 대체)
        RegionCondition condition = RegionCondition.builder()
                .crowdRateByPlaceName(Map.of("장소A", 20.0, "장소B", 40.0))
                .build();
        RecommendationCandidate candidate = RecommendationCandidate.builder().crowdRate(50.0).build();

        badgeAssembler.attach(List.of(candidate), condition);

        assertTrue(candidate.getBadges().stream().anyMatch(b -> b.getType() == Badge.BadgeType.CONGESTION
                && b.getLabel().contains("평소보다 혼잡")));
    }

    @Test
    void noCongestionBadgeWhenCloseToAverage() {
        RegionCondition condition = RegionCondition.builder()
                .crowdRateByPlaceName(Map.of("장소A", 40.0, "장소B", 45.0))
                .build();
        RecommendationCandidate candidate = RecommendationCandidate.builder().crowdRate(42.0).build();

        badgeAssembler.attach(List.of(candidate), condition);

        assertTrue(candidate.getBadges().stream().noneMatch(b -> b.getType() == Badge.BadgeType.CONGESTION));
    }

    @Test
    void hoursBadgeReflectsBusinessOpenFlag() {
        RegionCondition condition = RegionCondition.builder().crowdRateByPlaceName(Map.of()).build();
        RecommendationCandidate open = RecommendationCandidate.builder().businessOpen(true).build();
        RecommendationCandidate closed = RecommendationCandidate.builder().businessOpen(false).build();
        RecommendationCandidate unknown = RecommendationCandidate.builder().businessOpen(null).build();

        badgeAssembler.attach(List.of(open, closed, unknown), condition);

        assertTrue(open.getBadges().stream().anyMatch(b -> b.getType() == Badge.BadgeType.HOURS && b.getSeverity() == Badge.Severity.SUCCESS));
        assertTrue(closed.getBadges().stream().anyMatch(b -> b.getType() == Badge.BadgeType.HOURS && b.getSeverity() == Badge.Severity.WARNING));
        assertTrue(unknown.getBadges().stream().noneMatch(b -> b.getType() == Badge.BadgeType.HOURS));
    }
}
