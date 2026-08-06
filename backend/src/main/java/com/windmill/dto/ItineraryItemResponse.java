package com.windmill.dto;

import com.windmill.domain.ItineraryItem;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class ItineraryItemResponse {
    private Long itemId;
    private String contentId;
    private Integer contentTypeId;
    private String placeName;
    private String thumbnailUrl;
    private String scheduledTime;
    private List<String> tags;
    private Double crowdRate;
    private boolean isPinned;
    private String pinnedReason;
    private int displayOrder;
    private LocalDate visitDate;
    private String addr1;
    private String tel;
    private String useFeeText;
    private Boolean isFree;
    private String restDateText;
    private String mapX;
    private String mapY;

    public static ItineraryItemResponse from(ItineraryItem item) {
        return ItineraryItemResponse.builder()
                .itemId(item.getId())
                .contentId(item.getContentId())
                .contentTypeId(item.getContentTypeId())
                .placeName(item.getPlaceName())
                .thumbnailUrl(item.getThumbnailUrl())
                .scheduledTime(item.getScheduledTime())
                .tags(item.getTags())
                .crowdRate(item.getCrowdRate())
                .isPinned(item.isPinned())
                .pinnedReason(item.getPinnedReason())
                .displayOrder(item.getDisplayOrder())
                .visitDate(item.getVisitDate())
                .addr1(item.getAddr1())
                .tel(item.getTel())
                .useFeeText(item.getUseFeeText())
                .isFree(item.getIsFree())
                .restDateText(item.getRestDateText())
                .mapX(item.getMapX())
                .mapY(item.getMapY())
                .build();
    }
}
