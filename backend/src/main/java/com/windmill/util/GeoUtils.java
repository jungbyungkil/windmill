package com.windmill.util;

/**
 * TourAPI의 mapx/mapy는 WGS84 경도/위도(십진도)라 별도 좌표계 변환 없이 하버사인 공식으로
 * 직선거리(km)를 바로 계산할 수 있다 (기상청 격자 nx/ny와는 다른 좌표계이니 혼동 주의).
 */
public final class GeoUtils {

    private static final double EARTH_RADIUS_KM = 6371.0;
    public static final int DEFAULT_TRAVEL_MINUTES = 10;
    public static final int MIN_TRAVEL_MINUTES = 5;
    public static final int MAX_TRAVEL_MINUTES = 50;
    /** 시내 택시·버스 혼합. 도보(약 12분/km)보다 짧게 잡아 가까운 장소를 이어서 담기 쉽게 한다. */
    public static final double TRAVEL_MINUTES_PER_KM = 5.0;

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

    /**
     * 시내 당일치기 이동시간 추정. 좌표가 없으면 기본값.
     */
    public static int estimateTravelMinutes(String lon1, String lat1, String lon2, String lat2) {
        Double km = distanceKmSafe(lon1, lat1, lon2, lat2);
        if (km == null) {
            return DEFAULT_TRAVEL_MINUTES;
        }
        int travel = (int) Math.ceil(km * TRAVEL_MINUTES_PER_KM);
        return Math.max(MIN_TRAVEL_MINUTES, Math.min(travel, MAX_TRAVEL_MINUTES));
    }

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
