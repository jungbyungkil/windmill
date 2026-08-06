package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 실시간 변수(날씨·혼잡·동선)를 반영한 하루 스마트 일정 응답.
 * TourAPI 후보만 사용하며, 혼잡↓ 정렬 후 최근접 동선으로 재배치한다.
 */
@Data
@Builder
public class SmartPlanResponse {
    /** 사용자에게 보여줄 전략 요약 (예: 혼잡도 낮은 곳 우선 · 동선 최소화) */
    private String strategySummary;
    private boolean weatherAdjusted;
    private boolean heatAdjusted;
    private boolean crowdFiltered;
    private Double estimatedTotalDistanceKm;
    private int candidateCount;
    private List<RecommendationCandidate> stops;
}
