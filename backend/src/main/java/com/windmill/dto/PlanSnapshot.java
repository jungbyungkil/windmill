package com.windmill.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 특정 시점의 일정(장소 목록) 전체 스냅샷. 원본(original) 보관과 변경 이력 각 항목의
 * "그 시점 일정"을 담는 데 같은 구조를 쓴다. 되돌리기는 이 스냅샷으로 itinerary_item을 재구성한다.
 *
 * <p>스키마 진화 대비: 알 수 없는 필드는 무시하도록 {@code @JsonIgnoreProperties(ignoreUnknown = true)}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlanSnapshot {

    /** KST ISO-8601 (예: "2026-09-07T14:30:00+09:00") */
    private String capturedAt;

    @Builder.Default
    private List<Stop> stops = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Stop {
        private String contentId;
        private Integer contentTypeId;
        private String placeName;
        private String thumbnailUrl;
        private String mapX;
        private String mapY;
        /** "HH:mm" 또는 null */
        private String scheduledTime;
        /** ISO date "yyyy-MM-dd" 또는 null */
        private String visitDate;
        private Integer displayOrder;
        private String category;
        private boolean alternate;
    }
}
