package com.windmill.dto;

import lombok.Data;

@Data
public class UpdateItineraryItemRequest {
    private Boolean isPinned;
    private String pinnedReason;
    private Integer displayOrder;
    private String scheduledTime;
}
