package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

/** 동선 꼬임 감지 결과 - trigger-status에 함께 실어 프론트가 자동 재배치를 제안한다 */
@Data
@Builder
public class RouteTangleResult {
    private boolean tangled;
    private double currentDistanceKm;
    private double optimizedDistanceKm;
    private double wasteRatio;
    private String message;
}
