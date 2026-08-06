package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 대안 코스 추천 응답 - 우천/폭염 대체(실내 재검색)가 실제로 적용됐는지 프론트에 알려주기 위한 reason 포함 */
@Data
@Builder
public class AlternativesResponse {
    private List<RecommendationCandidate> candidates;
    /** "RAIN_ALTERNATIVE" | "HEAT_ALTERNATIVE" - 실내 카테고리 재검색이 적용된 경우, 그 외 null */
    private String reason;
}
