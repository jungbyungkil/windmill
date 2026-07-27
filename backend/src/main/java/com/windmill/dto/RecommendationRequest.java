package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RecommendationRequest {
    /** 연관관광지 조회 기준이 되는 장소명 (예: 이미 담은 일정 중 한 곳, 혹은 자연어검색 키워드). null이면 지역 전체 기준 */
    private String seedPlaceName;
    private List<String> tags;             // #아이동반 #실내 등
    private String naturalLanguageQuery;   // "아이랑 갈만한 곳"
    private List<String> excludeContentIds;
    /** 트리거 우선회피 정렬 힌트 - 대안 추천 시에만 사용 */
    private AvoidanceHint avoidanceHint;

    public enum AvoidanceHint { CROWD, WEATHER, BUSINESS }
}
