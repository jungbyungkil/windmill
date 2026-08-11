package com.windmill.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisitOrderOptimizerTest {

    record Spot(String id, String lon, String lat) {
    }

    private static final Function<Spot, String> LON = Spot::lon;
    private static final Function<Spot, String> LAT = Spot::lat;

    @Test
    void bruteForcePicksStraightLineOrder() {
        // A -- B -- C on a line; A→C→B is longer than A→B→C
        Spot a = new Spot("a", "127.0", "37.0");
        Spot b = new Spot("b", "127.05", "37.0");
        Spot c = new Spot("c", "127.10", "37.0");

        List<Spot> ordered = VisitOrderOptimizer.optimize(List.of(a, c, b), LON, LAT);

        double best = VisitOrderOptimizer.pathDistanceKm(ordered, null, null, LON, LAT);
        double bad = VisitOrderOptimizer.pathDistanceKm(List.of(a, c, b), null, null, LON, LAT);
        assertTrue(best <= bad + 0.01);
        // open path optimum is endpoints as ends: a-b-c or c-b-a
        assertEquals("b", ordered.get(1).id());
    }

    @Test
    void originForcesNearestFirstStop() {
        Spot far = new Spot("far", "128.0", "37.0");
        Spot near = new Spot("near", "127.01", "37.0");
        Spot mid = new Spot("mid", "127.05", "37.0");

        List<Spot> ordered = VisitOrderOptimizer.optimizeFromOrigin(
                List.of(far, mid, near), "127.0", "37.0", LON, LAT);

        assertEquals("near", ordered.get(0).id());
    }

    @Test
    void travelMinutesMatrixPicksFasterRoadOrder() {
        Spot a = new Spot("a", "127.0", "37.0");
        Spot b = new Spot("b", "127.05", "37.0");
        Spot c = new Spot("c", "127.10", "37.0");
        // 직선은 a-b-c가 짧지만, 도로로는 a→c가 매우 빠르고 c→b도 빠름 → a-c-b 선호
        int[][] minutes = {
                {0, 40, 10},
                {40, 0, 40},
                {10, 15, 0},
        };

        List<Spot> ordered = VisitOrderOptimizer.optimizeWithTravelMinutes(
                List.of(a, b, c), minutes, null);

        assertEquals("a", ordered.get(0).id());
        assertEquals("c", ordered.get(1).id());
        assertEquals("b", ordered.get(2).id());
    }

    @Test
    void placesWithoutCoordsGoLast() {
        Spot a = new Spot("a", "127.0", "37.0");
        Spot b = new Spot("b", "127.05", "37.0");
        Spot no = new Spot("no", null, null);

        List<Spot> ordered = VisitOrderOptimizer.optimize(List.of(no, b, a), LON, LAT);
        assertEquals("no", ordered.get(ordered.size() - 1).id());
    }
}
