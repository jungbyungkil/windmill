package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 지도 폴리라인용 경로. roadBased=false면 Haversine 직선 폴백 */
@Data
@Builder
public class MapRouteResponse {
    private List<LatLng> path;
    private Integer distanceMeters;
    private Integer durationSeconds;
    private boolean roadBased;
    private String message;
    private TransportMode mode;
    /** true면 실제 경로가 아닌 직선거리+평균속도 기반 추정치(WALK/TRANSIT) */
    private boolean estimated;

    @Data
    @Builder
    public static class LatLng {
        private double lat;
        private double lng;
    }
}
