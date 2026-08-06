package com.windmill.dto;

import com.windmill.domain.PlaceCategory;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 카테고리별 추천 장소 묶음 - API 데이터 기반, DB 미사용 */
@Data
@Builder
public class CategoryPlaceGroup {
    private PlaceCategory category;
    private String label;
    private String subLabel;
    private List<RecommendationCandidate> places;
}
