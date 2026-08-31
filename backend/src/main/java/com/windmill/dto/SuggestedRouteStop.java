package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 그리디 제안·현재 일정 비교용 한 줄.
 * REST_DAY / CLOSING / TOO_LATE / TIME_OVERLAP / NO_COORDS
 */
@Data
@Builder
public class SuggestedRouteStop {
    private Long itemId;
    private String placeName;
    private String scheduledTime;
    private boolean visitHardToday;
    private String hardTodayReason;
    private String hardTodayLabel;
    private Integer stayMinutes;
    private Integer travelMinutesFromPrev;
    private Integer contentTypeId;
}
