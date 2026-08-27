package com.windmill.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeoUtilsTest {

    @Test
    void estimateTravelMinutes_missingCoords_usesDefault() {
        assertEquals(GeoUtils.DEFAULT_TRAVEL_MINUTES, GeoUtils.estimateTravelMinutes(null, null, "127", "37"));
    }

    @Test
    void estimateTravelMinutes_nearbyPlaces_useMinimum() {
        // 약 0.2km → 1분 계산이지만 하한 5분
        int minutes = GeoUtils.estimateTravelMinutes("127.0000", "37.0000", "127.0020", "37.0000");
        assertEquals(GeoUtils.MIN_TRAVEL_MINUTES, minutes);
    }

    @Test
    void estimateTravelMinutes_twoKm_isAboutTenMinutes() {
        // 경도 0.022° ≈ 2km (위도 37°에서)
        Double km = GeoUtils.distanceKmSafe("127.0000", "37.0000", "127.0225", "37.0000");
        int minutes = GeoUtils.estimateTravelMinutes("127.0000", "37.0000", "127.0225", "37.0000");
        assertEquals((int) Math.ceil(km * GeoUtils.TRAVEL_MINUTES_PER_KM), minutes);
    }
}
