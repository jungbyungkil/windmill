package com.windmill.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 장소 추천 화면의 고정 카테고리 4종.
 * TourAPI contentTypeId/키워드로 매핑하며, 방문자(집중률) 데이터로 정렬한다.
 */
@Getter
@RequiredArgsConstructor
public enum PlaceCategory {
    RESTAURANT("식당", "레스토랑", 39, new String[]{"음식점", "식당", "맛집"}, new String[]{"카페", "커피", "디저트"}),
    MUSEUM("박물관", "전시관", 14, new String[]{"박물관", "전시관", "미술관", "전시"}, new String[]{}),
    KIDS_CAFE("키즈카페", "워터파크", null, new String[]{"키즈카페", "워터파크", "키즈카페"}, new String[]{}),
    CAFE("카페", "카페", 39, new String[]{"카페", "커피"}, new String[]{});

    private final String label;
    private final String subLabel;
    /** TourAPI contentTypeId. null이면 키워드 검색만 사용 */
    private final Integer contentTypeId;
    private final String[] searchKeywords;
    /** 결과에서 제외할 제목 키워드 (식당에서 카페 제외 등) */
    private final String[] excludeTitleKeywords;
}
