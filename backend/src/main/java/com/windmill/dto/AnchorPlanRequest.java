package com.windmill.dto;

import lombok.Data;

/**
 * 목적지 직접 선택 플로우 요청 - 사용자가 이름으로 검색해 고른 장소(anchor)를 그대로 되돌려 받아
 * 재사용한다(추가 상세조회 없이). durationMinutes/slot으로 그 장소의 체류시간·오전/오후 배치만 정한다.
 */
@Data
public class AnchorPlanRequest {
    private RecommendationCandidate anchor;
    private int durationMinutes;
    private DaySlot slot;
}
