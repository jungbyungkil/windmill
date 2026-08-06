package com.windmill.dto;

import lombok.Data;

@Data
public class UpdateItineraryItemRequest {
    private Boolean isPinned;
    private String pinnedReason;
    private Integer displayOrder;
    private String scheduledTime;
    /** 다른 날로 옮기기 - "YYYY-MM-DD" */
    private String visitDate;
}
