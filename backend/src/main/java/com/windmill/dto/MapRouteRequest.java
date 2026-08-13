package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 카카오 모빌리티 길찾기 프록시 요청 — WGS84 경도/위도 */
@Data
@Builder
public class MapRouteRequest {
    /** 방문 순서대로. 최소 2개 */
    private List<MapPoint> points;
    /** 미지정 시 CAR */
    private TransportMode mode;

    @Data
    @Builder
    public static class MapPoint {
        private double lon;
        private double lat;
        private String name;
    }
}
