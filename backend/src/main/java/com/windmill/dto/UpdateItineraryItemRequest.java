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
}
