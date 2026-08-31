package com.windmill.util;

import java.util.Locale;

/**
 * 위치기반 검색 캐시 키. 동일 좌표(소수점 3자리 ≈ 100m) · 반경 · 페이지 조합을 묶는다.
 */
public final class LocationCacheKeys {

    public static final int COORD_DECIMALS = 3;

    private LocationCacheKeys() {
    }

    public static String nearby(double mapX, double mapY, int radius, Integer contentTypeId,
                                int pageNo, int numOfRows) {
        return "nearby:" + formatCoord(mapX) + ":" + formatCoord(mapY) + ":" + radius + ":"
                + (contentTypeId == null ? "all" : contentTypeId) + ":" + pageNo + ":" + numOfRows;
    }

    public static double round(double value) {
        double factor = Math.pow(10, COORD_DECIMALS);
        return Math.round(value * factor) / factor;
    }

    public static String formatCoord(double value) {
        return String.format(Locale.US, "%." + COORD_DECIMALS + "f", round(value));
    }
}
