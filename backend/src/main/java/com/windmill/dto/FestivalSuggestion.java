package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

/** 여행 기간과 겹치는 지역 축제/행사 - "이 날짜에 이 축제 어때요?" 트리거 카드에 쓰인다 */
@Data
@Builder
public class FestivalSuggestion {
    private String contentId;
    private Integer contentTypeId;
    private String placeName;
    private String thumbnailUrl;
    private String addr1;
    /** yyyyMMdd 원문 그대로 - 프론트에서 표시 형식으로 가공 */
    private String eventStartDate;
    private String eventEndDate;
    /** detailCommon2 homepage에서 추출한 외부 URL (없으면 null) */
    private String homepageUrl;
}
