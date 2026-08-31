package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 지도 위치기반 검색 결과. locationBasedList2 필드만 담으며 관광 원본은 DB에 저장하지 않는다.
 * 일정 추가는 프론트가 사용자 생성 데이터(contentId·좌표·이름)로 addItem 한다.
 */
@Data
@Builder
public class NearbyPlaceResponse {
    private String contentId;
    private Integer contentTypeId;
    private String placeName;
    private String addr1;
    private String mapX;
    private String mapY;
    private String thumbnailUrl;
    private String tel;
    private String cat1;
    private String cat2;
    private String cat3;
    /** contentTypeId 기준 한글 카테고리 (음식점/관광지 등) */
    private String category;
    /** 중심 좌표로부터 거리(m). TourAPI dist */
    private Integer dist;
    private HoursPhase hoursPhase;
}
