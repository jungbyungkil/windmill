package com.windmill.client;

import com.windmill.dto.MapRouteRequest;
import com.windmill.dto.MapRouteResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KakaoDirectionsClientTest {

    @Test
    void straightFallbackConnectsPointsInOrder() {
        List<MapRouteRequest.MapPoint> points = List.of(
                MapRouteRequest.MapPoint.builder().lon(127.0).lat(37.0).build(),
                MapRouteRequest.MapPoint.builder().lon(127.05).lat(37.0).build()
        );
        MapRouteResponse res = KakaoDirectionsClient.straightFallback(points, "직선");
        assertFalse(res.isRoadBased());
        assertEquals(2, res.getPath().size());
        assertEquals(37.0, res.getPath().get(0).getLat());
        assertEquals(127.05, res.getPath().get(1).getLng());
        assertTrue(res.getDistanceMeters() != null && res.getDistanceMeters() > 0);
    }
}
