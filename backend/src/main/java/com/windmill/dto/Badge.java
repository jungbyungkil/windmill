package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

/** 추천 카드 실시간 상태 배지 - 날씨/혼잡/영업 3종 */
@Data
@Builder
public class Badge {
    private BadgeType type;
    private String label;
    private Severity severity;

    public enum BadgeType { WEATHER, CONGESTION, HOURS }

    public enum Severity { INFO, WARNING, SUCCESS, DANGER }
}
