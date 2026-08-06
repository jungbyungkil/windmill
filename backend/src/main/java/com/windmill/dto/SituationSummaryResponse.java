package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 앱 실행 시 현재 위치 기반 상황 요약 */
@Data
@Builder
public class SituationSummaryResponse {
    private String regionCode;
    private String regionDisplayName;
    private Double temperature;
    private Double precipitationProbability;
    private String weatherLabel;
    private boolean heatAlert;
    private boolean rainAlert;
    private Integer crowdedPlaceCount;
    private String headline;
    private String detail;
    private List<String> tips;
}
