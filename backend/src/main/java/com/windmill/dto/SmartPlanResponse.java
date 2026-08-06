package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 실시간 변수(날씨·혼잡·동선)를 반영한 스마트 일정 응답.
 * 다일 여행이면 days[]에 일차별로 나뉘고, stops는 전체 평탄화 목록이다.
 */
@Data
@Builder
public class SmartPlanResponse {
    /** 사용자에게 보여줄 전략 요약 */
    private String strategySummary;
    private boolean weatherAdjusted;
    private boolean heatAdjusted;
    private boolean crowdFiltered;
    private Double estimatedTotalDistanceKm;
    private int candidateCount;
    /** 요청한 특정 일자(없으면 전체 여행) */
    private String visitDate;
    private int dayCount;
    private List<DayPlan> days;
    /** 전체 일차 stops 평탄화 (하위 호환) */
    private List<RecommendationCandidate> stops;

    @Data
    @Builder
    public static class DayPlan {
        private int dayIndex;
        private String visitDate;
        private String label;
        private Double estimatedDistanceKm;
        private List<RecommendationCandidate> stops;
    }
}
