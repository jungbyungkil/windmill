package com.windmill.dto;

import com.windmill.domain.ItineraryItem;
import com.windmill.util.PlaceTagSanitizer;
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
    private String closeTime;
    private String useTimeText;
    private String homepageUrl;
    private Boolean strollerFriendly;
    private boolean accessibleFriendly;
    private String mapX;
    private String mapY;
    private boolean isAlternate;

    public static ItineraryItemResponse from(ItineraryItem item) {
        // 이미 저장된 #맛집 오탐도 응답 시점에 바로잡음 (체험관·스테이션 등)
        List<String> tags = PlaceTagSanitizer.sanitizeStored(
                item.getTags(), item.getContentTypeId(), item.getPlaceName(), null);
        return ItineraryItemResponse.builder()
                .itemId(item.getId())
                .contentId(item.getContentId())
                .contentTypeId(item.getContentTypeId())
                .placeName(item.getPlaceName())
                .thumbnailUrl(item.getThumbnailUrl())
                .scheduledTime(item.getScheduledTime())
                .tags(tags)
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
                .closeTime(item.getCloseTime())
                .useTimeText(item.getUseTimeText())
                .homepageUrl(item.getHomepageUrl())
                .strollerFriendly(item.getStrollerFriendly())
                .accessibleFriendly(item.isAccessibleFriendly())
                .mapX(item.getMapX())
                .mapY(item.getMapY())
                .isAlternate(item.isAlternate())
                .build();
    }
}
