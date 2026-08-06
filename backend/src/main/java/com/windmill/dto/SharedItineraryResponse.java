package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 일정 공유용 공개 스냅샷 - 세션 정보 제외 */
@Data
@Builder
public class SharedItineraryResponse {
    private String shareToken;
    private String regionDisplayName;
    private String startDate;
    private String endDate;
    private String companionType;
    private boolean withPet;
    private List<SharedItem> items;
    private String shareUrlPath;

    @Data
    @Builder
    public static class SharedItem {
        private String placeName;
        private String scheduledTime;
        private String visitDate;
        private String thumbnailUrl;
        private String category;
        private List<String> tags;
    }
}
