package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PlaceHoursWarning {
    /** REST_DAY | HOLIDAY_SHIFT | CLOSING_SOON */
    private String code;
    private String message;
    /** 정기휴무 원문 등 보조 설명 */
    private String detail;
}
