package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 중기예보(대략 3~10일) 요약 - 단기보다 거친 전망 */
@Data
@Builder
public class MidTermForecastResponse {
    private String regionLabel;
    private String landRegId;
    private String taRegId;
    private String tmFc;
    private String summary;
    private List<DayOutlook> days;

    @Data
    @Builder
    public static class DayOutlook {
        /** 오늘 기준 +N일 (3~10) */
        private int dayOffset;
        private String date;
        private String weekday;
        private String amWeather;
        private String pmWeather;
        private Integer amRainPercent;
        private Integer pmRainPercent;
        private Integer minTemp;
        private Integer maxTemp;
        private boolean rainRisk;
        private boolean heatRisk;
    }
}
