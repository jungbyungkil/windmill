package com.windmill.dto;

import lombok.Data;

import java.util.List;

@Data
public class UpdateItineraryItemRequest {
    private Boolean isPinned;
    private String pinnedReason;
    private Integer displayOrder;
    private String scheduledTime;
    /** 다른 날로 옮기기 - "YYYY-MM-DD" */
    private String visitDate;

    /** 장소명·태그·부가정보 수정 (기존/추천 담은 항목 공통) */
    private String placeName;
    private List<String> tags;
    private String addr1;
    private String tel;
    private String useFeeText;
    private Boolean isFree;
    private Integer estimatedCostPerPerson;
    private String restDateText;
    private String category;
    /** 상황 태그 수동 보정 — null이면 기존 값 유지 */
    private Boolean indoorYn;
    private com.windmill.domain.RainSensitivity rainSensitivity;
    private com.windmill.domain.CongestionSensitivity congestionSensitivity;
    /** 휴무·마감 경고를 사용자가 확인한 뒤의 시간 수정. 마감 게이트만 건너뛴다. */
    private Boolean acknowledgeHoursWarning;
}
