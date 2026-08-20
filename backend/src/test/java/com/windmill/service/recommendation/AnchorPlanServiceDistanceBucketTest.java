package com.windmill.service.recommendation;

import com.windmill.dto.RecommendationCandidate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 앵커 주변 채움에서 거리 "값" 완전 재정렬 대신 근/중/원 버킷으로 바뀐 로직 검증 (SmartPlanService.sortComfortable과 동일 원칙) */
class AnchorPlanServiceDistanceBucketTest {

    @Test
    void nearBucket() {
        RecommendationCandidate c = RecommendationCandidate.builder().distanceKm(1.0).build();
        assertEquals(0, AnchorPlanService.distanceBucket(c));
    }

    @Test
    void midBucket() {
        RecommendationCandidate c = RecommendationCandidate.builder().distanceKm(3.0).build();
        assertEquals(1, AnchorPlanService.distanceBucket(c));
    }

    @Test
    void farBucket() {
        RecommendationCandidate c = RecommendationCandidate.builder().distanceKm(10.0).build();
        assertEquals(3, AnchorPlanService.distanceBucket(c));
    }

    @Test
    void unknownDistanceBucket() {
        RecommendationCandidate c = RecommendationCandidate.builder().distanceKm(null).build();
        assertEquals(2, AnchorPlanService.distanceBucket(c));
    }
}
