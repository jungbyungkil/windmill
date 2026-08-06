package com.windmill.util;

import com.windmill.dto.RecommendationCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteOptimizerTest {

    @Test
    void ordersByNearestNeighborFromLowestCrowd() {
        RecommendationCandidate a = RecommendationCandidate.builder()
                .contentId("a").placeName("A").crowdRate(10.0)
                .mapX("127.0").mapY("37.0").build();
        RecommendationCandidate b = RecommendationCandidate.builder()
                .contentId("b").placeName("B").crowdRate(40.0)
                .mapX("127.05").mapY("37.0").build();
        RecommendationCandidate c = RecommendationCandidate.builder()
                .contentId("c").placeName("C").crowdRate(20.0)
                .mapX("127.10").mapY("37.0").build();

        List<RecommendationCandidate> ordered = RouteOptimizer.optimize(List.of(b, c, a));

        assertEquals("a", ordered.get(0).getContentId());
        assertEquals("b", ordered.get(1).getContentId());
        assertEquals("c", ordered.get(2).getContentId());
        assertTrue(ordered.get(1).getDistanceKm() != null && ordered.get(1).getDistanceKm() < 10);
    }

    @Test
    void totalDistanceIsSumOfLegs() {
        RecommendationCandidate a = RecommendationCandidate.builder()
                .contentId("a").placeName("A").mapX("127.0").mapY("37.0").build();
        RecommendationCandidate b = RecommendationCandidate.builder()
                .contentId("b").placeName("B").mapX("127.05").mapY("37.0").build();
        double total = RouteOptimizer.totalDistanceKm(List.of(a, b));
        assertTrue(total > 0);
        assertEquals(null, a.getDistanceKm());
        assertTrue(b.getDistanceKm() != null);
    }
}
