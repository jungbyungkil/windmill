package com.windmill.util;

/**
 * TourAPI의 mapx/mapy는 WGS84 경도/위도(십진도)라 별도 좌표계 변환 없이 하버사인 공식으로
 * 직선거리(km)를 바로 계산할 수 있다 (기상청 격자 nx/ny와는 다른 좌표계이니 혼동 주의).
 */
public final class GeoUtils {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private GeoUtils() {
    }

    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }

    /** mapx/mapy 문자열 파싱 실패(빈 값/비정상 포맷)에 안전한 버전 - 실패 시 null */
    public static Double distanceKmSafe(String lon1, String lat1, String lon2, String lat2) {
        try {
            if (lon1 == null || lat1 == null || lon2 == null || lat2 == null) {
                return null;
            }
            return haversineKm(Double.parseDouble(lat1), Double.parseDouble(lon1),
                    Double.parseDouble(lat2), Double.parseDouble(lon2));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
