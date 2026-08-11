package com.windmill.service.recommendation;

import com.windmill.dto.Badge;
import com.windmill.dto.RecommendationCandidate;
import com.windmill.service.trigger.RegionCondition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BadgeAssemblerTest {

    private final BadgeAssembler assembler = new BadgeAssembler();

    @Test
    void heatAdvisoryUsesTmxProxy() {
        RegionCondition condition = RegionCondition.builder()
                .crowdRateByPlaceName(Map.of())
                .dailyMaxTemp(33.5)
                .build();
        RecommendationCandidate candidate = RecommendationCandidate.builder().placeName("A").build();
        assembler.attach(List.of(candidate), condition);
        assertTrue(candidate.getBadges().stream()
                .anyMatch(b -> b.getType() == Badge.BadgeType.WEATHER
                        && b.getSeverity() == Badge.Severity.WARNING
                        && b.getLabel().contains("주의보")));
    }

    @Test
    void heatWarningAt35() {
        RegionCondition condition = RegionCondition.builder()
                .crowdRateByPlaceName(Map.of())
                .dailyMaxTemp(36.0)
                .build();
        RecommendationCandidate candidate = RecommendationCandidate.builder().placeName("A").build();
        assembler.attach(List.of(candidate), condition);
        assertTrue(candidate.getBadges().stream()
                .anyMatch(b -> b.getSeverity() == Badge.Severity.DANGER
                        && b.getLabel().contains("경보")));
    }

    @Test
    void rainBadgeWhenNoHeat() {
        RegionCondition condition = RegionCondition.builder()
                .crowdRateByPlaceName(Map.of())
                .currentPop(70.0)
                .currentPopFcstTime("1400")
                .build();
        RecommendationCandidate candidate = RecommendationCandidate.builder().placeName("A").build();
        assembler.attach(List.of(candidate), condition);
        assertTrue(candidate.getBadges().stream()
                .anyMatch(b -> b.getType() == Badge.BadgeType.WEATHER && b.getLabel().contains("비")));
    }

    @Test
    void crowdCategoryBoomBeam() {
        RegionCondition condition = RegionCondition.builder()
                .crowdRateByPlaceName(Map.of("장소A", 40.0))
                .crowdCategoryByPlaceName(Map.of("장소A", "붐빔"))
                .build();
        RecommendationCandidate candidate = RecommendationCandidate.builder()
                .placeName("장소A")
                .crowdRate(40.0)
                .build();
        assembler.attach(List.of(candidate), condition);
        assertTrue(candidate.getBadges().stream()
                .anyMatch(b -> b.getType() == Badge.BadgeType.CONGESTION));
    }

    @Test
    void crowdRelativePercentWarning() {
        RegionCondition condition = RegionCondition.builder()
                .crowdRateByPlaceName(Map.of("장소A", 75.0))
                .crowdRelativePercentByPlaceName(Map.of("장소A", 160.0))
                .build();
        RecommendationCandidate candidate = RecommendationCandidate.builder()
                .placeName("장소A")
                .crowdRate(75.0)
                .build();
        assembler.attach(List.of(candidate), condition);
        assertTrue(candidate.getBadges().stream()
                .anyMatch(b -> b.getType() == Badge.BadgeType.CONGESTION
                        && b.getLabel().contains("평소 대비")));
    }

    @Test
    void peakRateFallbackBusy() {
        RegionCondition condition = RegionCondition.builder()
                .crowdRateByPlaceName(Map.of())
                .build();
        RecommendationCandidate candidate = RecommendationCandidate.builder()
                .placeName("장소A")
                .crowdRate(95.0)
                .build();
        assembler.attach(List.of(candidate), condition);
        assertEquals(1, candidate.getBadges().stream()
                .filter(b -> b.getType() == Badge.BadgeType.CONGESTION).count());
    }

    @Test
    void businessOpenBadge() {
        RegionCondition condition = RegionCondition.builder().crowdRateByPlaceName(Map.of()).build();
        RecommendationCandidate open = RecommendationCandidate.builder().businessOpen(true).build();
        RecommendationCandidate closed = RecommendationCandidate.builder().businessOpen(false).build();
        RecommendationCandidate unknown = RecommendationCandidate.builder().businessOpen(null).build();
        assembler.attach(List.of(open, closed, unknown), condition);
        assertTrue(open.getBadges().stream().anyMatch(b -> "영업중".equals(b.getLabel())));
        assertTrue(closed.getBadges().stream().anyMatch(b -> b.getLabel().contains("영업종료")));
        assertTrue(unknown.getBadges() == null || unknown.getBadges().stream()
                .noneMatch(b -> b.getType() == Badge.BadgeType.HOURS));
    }
}
