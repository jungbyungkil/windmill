package com.windmill.dto;

import com.windmill.domain.ItineraryItem;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ItineraryItemResponse {
    private Long itemId;
    private String contentId;
    private Integer contentTypeId;
    private String placeName;
    private String scheduledTime;
    private List<String> tags;
    private Double crowdRate;
    private boolean isPinned;
    private String pinnedReason;
    private int displayOrder;

    public static ItineraryItemResponse from(ItineraryItem item) {
        return ItineraryItemResponse.builder()
                .itemId(item.getId())
                .contentId(item.getContentId())
                .contentTypeId(item.getContentTypeId())
                .placeName(item.getPlaceName())
                .scheduledTime(item.getScheduledTime())
                .tags(item.getTags())
                .crowdRate(item.getCrowdRate())
                .isPinned(item.isPinned())
                .pinnedReason(item.getPinnedReason())
                .displayOrder(item.getDisplayOrder())
                .build();
    }
}
