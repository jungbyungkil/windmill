package com.windmill.dto;

import lombok.Data;

/**
 * 앵커(고정 일정) 등록 요청 - 예: DDP에서 19:00 공연처럼 이미 시각이 정해진 장소를 하루 일정의
 * 기준점으로 삼고, 그 앞뒤 빈 시간대(식사·가벼운 관광)를 자동으로 채운다.
 * anchor는 이름 검색(/api/recommendations/search) 결과를 그대로 되돌려 받아 재사용한다.
 */
@Data
public class AnchorPlanRequest {
    private RecommendationCandidate anchor;
    /** "HH:mm" - 공연/이벤트 시작 시각 등 이미 정해진 고정 시각 */
    private String anchorTime;
}
