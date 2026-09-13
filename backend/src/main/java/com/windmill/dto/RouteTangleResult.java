package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

/** 동선 꼬임 감지 결과 - trigger-status에 함께 실어 프론트가 자동 재배치를 제안한다 */
@Data
@Builder
public class RouteTangleResult {
    private boolean tangled;
    /** Haversine 기준(항상 있음). 카카오 실제 도로 데이터를 못 구하면 화면 표시도 이 값을 그대로 쓴다. */
    private Double currentDistanceKm;
    private Double optimizedDistanceKm;
    /** 카카오 실제 도로 소요시간(분) - 구했을 때만 채워짐(선택). 화면 표시는 이 값을 분 단위로 우선 노출. */
    private Integer currentDurationMinutes;
    private Integer optimizedDurationMinutes;
    private double wasteRatio;
    private String message;
}
