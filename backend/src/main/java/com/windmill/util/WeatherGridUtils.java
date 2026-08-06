package com.windmill.util;

/**
 * 기상청 단기예보 격자(nx, ny) ↔ WGS84 변환 (공식 변환식).
 * 위치기반 상황 요약에서 사용자 좌표를 가장 가까운 시군구 격자에 매칭할 때 사용한다.
 */
public final class WeatherGridUtils {

    private static final double RE = 6371.00877;
    private static final double GRID = 5.0;
    private static final double SLAT1 = 30.0;
    private static final double SLAT2 = 60.0;
    private static final double OLON = 126.0;
    private static final double OLAT = 38.0;
    private static final double XO = 43;
    private static final double YO = 136;

    private WeatherGridUtils() {
    }

    public record Grid(int nx, int ny) {
    }

    public static Grid toGrid(double lat, double lon) {
        double degToRad = Math.PI / 180.0;
        double re = RE / GRID;
        double slat1 = SLAT1 * degToRad;
        double slat2 = SLAT2 * degToRad;
        double olon = OLON * degToRad;
        double olat = OLAT * degToRad;

        double sn = Math.tan(Math.PI * 0.25 + slat2 * 0.5) / Math.tan(Math.PI * 0.25 + slat1 * 0.5);
        sn = Math.log(Math.cos(slat1) / Math.cos(slat2)) / Math.log(sn);
        double sf = Math.tan(Math.PI * 0.25 + slat1 * 0.5);
        sf = Math.pow(sf, sn) * Math.cos(slat1) / sn;
        double ro = Math.tan(Math.PI * 0.25 + olat * 0.5);
        ro = re * sf / Math.pow(ro, sn);

        double ra = Math.tan(Math.PI * 0.25 + lat * degToRad * 0.5);
        ra = re * sf / Math.pow(ra, sn);
        double theta = lon * degToRad - olon;
        if (theta > Math.PI) {
            theta -= 2.0 * Math.PI;
        }
        if (theta < -Math.PI) {
            theta += 2.0 * Math.PI;
        }
        theta *= sn;

        int nx = (int) Math.floor(ra * Math.sin(theta) + XO + 0.5);
        int ny = (int) Math.floor(ro - ra * Math.cos(theta) + YO + 0.5);
        return new Grid(nx, ny);
    }
}
