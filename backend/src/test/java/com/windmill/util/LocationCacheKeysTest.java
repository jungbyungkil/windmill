package com.windmill.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class LocationCacheKeysTest {

    @Test
    void nearbyKeyRoundsCoordsToThreeDecimals() {
        String a = LocationCacheKeys.nearby(126.97841, 37.56651, 1000, null, 1, 40);
        String b = LocationCacheKeys.nearby(126.97849, 37.56659, 1000, null, 1, 40);
        assertEquals(a, b);
        assertEquals("nearby:126.978:37.567:1000:all:1:40", a);
    }

    @Test
    void nearbyKeyDiffersByRadiusPageOrType() {
        String base = LocationCacheKeys.nearby(126.978, 37.566, 1000, null, 1, 40);
        assertNotEquals(base, LocationCacheKeys.nearby(126.978, 37.566, 500, null, 1, 40));
        assertNotEquals(base, LocationCacheKeys.nearby(126.978, 37.566, 1000, 39, 1, 40));
        assertNotEquals(base, LocationCacheKeys.nearby(126.978, 37.566, 1000, null, 2, 40));
    }
}
