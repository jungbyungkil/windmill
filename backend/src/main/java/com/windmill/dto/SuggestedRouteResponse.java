package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * "이 순서 어때요?" 미리보기. 일정에 쓰지 않고 비교용으로만 내려준다.
 */
@Data
@Builder
public class SuggestedRouteResponse {
    private String message;
    /** 현재 일정 순서(방문 시각 → displayOrder) */
    @Builder.Default
    private List<SuggestedRouteStop> currentStops = new ArrayList<>();
    /** 그리디 제안 순서. 영업시간 안 방문이 어렵면 visitHardToday=true 로 뒤에 붙는다. */
    @Builder.Default
    private List<SuggestedRouteStop> suggestedStops = new ArrayList<>();
    private boolean orderChanged;
    private boolean timesChanged;
    private int hardTodayCount;
    private Double currentDistanceKm;
    private Double suggestedDistanceKm;
    private Integer totalTravelMinutes;
    private boolean usedGpsOrigin;
}
