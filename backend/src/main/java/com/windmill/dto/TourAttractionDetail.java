package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * detailCommon2 + detailIntro2 + detailImage2 조합 결과.
 * introFields는 contentTypeId별로 응답 스키마가 달라 Map으로 유지 (예: usetime, restdate 등).
 */
@Data
@Builder
public class TourAttractionDetail {
    private String contentId;
    private String contentTypeId;
    private String title;
    private String overview;
    private String homepage;
    private String addr1;
    private String addr2;
    private String mapX;
    private String mapY;
    private String tel;
    private Map<String, String> introFields;
    private List<String> imageUrls;
}
