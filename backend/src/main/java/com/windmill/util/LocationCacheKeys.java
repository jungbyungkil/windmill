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
        return round(value, COORD_DECIMALS);
    }

    /** decimals 자리로 반올림 - 호출자가 COORD_DECIMALS(3자리≈100m)보다 촘촘한 정밀도가 필요할 때(예:
     * 동선 최적화의 좌표쌍 캐시 키, 4자리≈11m) 쓴다. */
    public static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    public static String formatCoord(double value) {
        return formatCoord(value, COORD_DECIMALS);
    }

    /** decimals 자리로 반올림해 Locale.US로 포맷 - 로케일에 따라 소수 구분자가 바뀌는(예: ','） JVM에서도
     * 캐시 키가 흔들리지 않게 한다. */
    public static String formatCoord(double value, int decimals) {
        return String.format(Locale.US, "%." + decimals + "f", round(value, decimals));
    }
}
