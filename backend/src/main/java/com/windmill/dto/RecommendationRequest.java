package com.windmill.dto;

import com.windmill.domain.AgeGroup;
import com.windmill.domain.CompanionType;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RecommendationRequest {
    /** 성인 대표 연령대 - AgeGroupRanking 가중치 기준 */
    private AgeGroup adultAgeGroup;
    /** 동반 자녀 만 나이 목록 - AgeGroupRanking이 expAgeRange 하한과 직접 비교 */
    private List<Integer> childAges;
    /** region-codes.json의 signguFullCode - 이 지역 기준으로 Stage1/3가 조회한다 */
    private String regionCode;
    /** true면 Stage1이 TarRlteTarService1 대신 반려동물 동반여행 전용 API를 후보 소스로 사용 */
    private boolean withPet;
    /** true면 유모차 이용 가능 확인된 후보를 앞으로 당김(AccessibilityRanking) - 소스 자체는 바꾸지 않음(전용 API 없음) */
    private boolean strollerFriendly;
    /** true면 무장애 키워드 매칭된 후보를 앞으로 당김(AccessibilityRanking) - 구조화 필드 없어 휴리스틱 */
    private boolean accessibleFriendly;
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
    /** true면 Stage4(LLM 태그·문장 생성)를 건너뛰고 키워드 기반 폴백만 쓴다 - 표준 스마트 일정처럼
     *  "최대한 빨리 큼직한 일정을 보여줘야" 하는 자동 생성 경로 전용. LLM은 순위를 안 바꾸므로
     *  건너뛰어도 어떤 장소가 뽑히는지는 동일하고, matchedTags/oneLiner만 더 단순해진다. */
    private boolean skipLlm;

    public enum AvoidanceHint { CROWD, WEATHER, HEAT, BUSINESS }
}
