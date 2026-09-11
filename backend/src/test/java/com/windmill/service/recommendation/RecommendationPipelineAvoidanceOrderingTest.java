package com.windmill.service.recommendation;

import com.windmill.domain.CongestionSensitivity;
import com.windmill.domain.RainSensitivity;
import com.windmill.dto.BusinessStatus;
import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RecommendationRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 트리거 대안(applyAvoidanceOrdering) 정렬 검증 - 2026-09-11 사용자 결정("혼잡 회피여도 인기순
 * 우선이 맞다, 사용자가 판단하게 해달라")로 인기순이 소프트 선호(혼잡 둔감·우천 둔감·거리 등)보다
 * 우선하도록 바뀜. 방문일 정기휴무(무효한 답)만 인기순보다 먼저 걸러지는 예외.
 */
class RecommendationPipelineAvoidanceOrderingTest {

    @Test
    void crowdAvoidance_popularityBeatsCongestionSensitivity() {
        RecommendationCandidate popularButSensitive = RecommendationCandidate.builder()
                .placeName("인기명소").rank(1)
                .congestionSensitivity(CongestionSensitivity.SENSITIVE)
                .crowdRate(80.0)
                .build();
        RecommendationCandidate lessPopularButInsensitive = RecommendationCandidate.builder()
                .placeName("한산한곳").rank(5)
                .congestionSensitivity(CongestionSensitivity.INSENSITIVE)
                .crowdRate(20.0)
                .build();

        List<RecommendationCandidate> sorted = RecommendationPipeline.applyAvoidanceOrdering(
                List.of(lessPopularButInsensitive, popularButSensitive),
                RecommendationRequest.AvoidanceHint.CROWD, null);

        assertEquals("인기명소", sorted.get(0).getPlaceName());
    }

    @Test
    void weatherAvoidance_popularityBeatsIndoorPreference() {
        RecommendationCandidate popularOutdoor = RecommendationCandidate.builder()
                .placeName("인기야외").rank(1)
                .indoor(false)
                .rainSensitivity(RainSensitivity.SENSITIVE)
                .build();
        RecommendationCandidate lessPopularIndoor = RecommendationCandidate.builder()
                .placeName("한산실내").rank(5)
                .indoor(true)
                .rainSensitivity(RainSensitivity.INSENSITIVE)
                .build();

        List<RecommendationCandidate> sorted = RecommendationPipeline.applyAvoidanceOrdering(
                List.of(lessPopularIndoor, popularOutdoor),
                RecommendationRequest.AvoidanceHint.WEATHER, null);

        assertEquals("인기야외", sorted.get(0).getPlaceName());
    }

    @Test
    void routeAvoidance_popularityBeatsDistanceAmongOpenPlaces() {
        RecommendationCandidate popularButFar = RecommendationCandidate.builder()
                .placeName("인기멀리").rank(1)
                .businessStatus(BusinessStatus.OPEN)
                .distanceKm(5.0)
                .build();
        RecommendationCandidate lessPopularButNear = RecommendationCandidate.builder()
                .placeName("한산가까이").rank(5)
                .businessStatus(BusinessStatus.OPEN)
                .distanceKm(0.5)
                .build();

        List<RecommendationCandidate> sorted = RecommendationPipeline.applyAvoidanceOrdering(
                List.of(lessPopularButNear, popularButFar),
                RecommendationRequest.AvoidanceHint.ROUTE, null);

        assertEquals("인기멀리", sorted.get(0).getPlaceName());
    }

    @Test
    void routeAvoidance_closedDayStillLosesToOpenRegardlessOfPopularity() {
        // 방문일 정기휴무는 "무효한 답"이라 인기순보다 먼저 걸러지는 유일한 예외
        RecommendationCandidate popularButClosed = RecommendationCandidate.builder()
                .placeName("인기휴무").rank(1)
                .businessStatus(BusinessStatus.CLOSED_DAY)
                .build();
        RecommendationCandidate lessPopularButOpen = RecommendationCandidate.builder()
                .placeName("한산영업").rank(5)
                .businessStatus(BusinessStatus.OPEN)
                .build();

        List<RecommendationCandidate> sorted = RecommendationPipeline.applyAvoidanceOrdering(
                List.of(popularButClosed, lessPopularButOpen),
                RecommendationRequest.AvoidanceHint.BUSINESS, null);

        assertEquals("한산영업", sorted.get(0).getPlaceName());
    }
}
