package com.windmill.dto;

import com.windmill.domain.CompanionType;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RecommendationRequest {
    /** region-codes.json의 signguFullCode - 이 지역 기준으로 Stage1/3가 조회한다 */
    private String regionCode;
    /** true면 Stage1이 TarRlteTarService1 대신 반려동물 동반여행 전용 API를 후보 소스로 사용 */
    private boolean withPet;
    /** 동반유형별 카테고리 가중치(LLM 미사용, CompanionCategoryRanking 참고) 적용 기준 */
    private CompanionType companionType;
    /** 연관관광지 조회 기준이 되는 장소명 (예: 이미 담은 일정 중 한 곳, 혹은 자연어검색 키워드). null이면 지역 전체 기준 */
    private String seedPlaceName;
    private List<String> tags;             // #아이동반 #실내 등
    private String naturalLanguageQuery;   // "아이랑 갈만한 곳"
    private List<String> excludeContentIds;
    /** 이 세션이 "별로"로 평가한 장소명 - 추천 정확도 개선 (기록 태깅 → 추천 반영) */
    private List<String> excludePlaceNames;
    /** 트리거 우선회피 정렬 힌트 - 대안 추천 시에만 사용 */
    private AvoidanceHint avoidanceHint;
    /** 거리(km) 계산 기준점 - 보통 일정에 이미 담긴 마지막 장소. null이면 distanceKm 없이 반환 */
    private String originContentId;
    private Integer originContentTypeId;

    public enum AvoidanceHint { CROWD, WEATHER, HEAT, BUSINESS }
}
