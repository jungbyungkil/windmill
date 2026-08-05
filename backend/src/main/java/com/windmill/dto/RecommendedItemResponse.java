package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

/** 여행기록 집계 슬롯 1건 - 브리프의 timeSlot/selectedCount에 더해, "일정에 추가" 액션에 필요한 검증된 후보 전체를 함께 담는다 */
@Data
@Builder
public class RecommendedItemResponse {
    private String timeSlot;
    private int selectedCount;
    private RecommendationCandidate candidate;
}
