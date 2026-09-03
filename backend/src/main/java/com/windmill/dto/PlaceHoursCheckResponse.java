package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PlaceHoursCheckResponse {
    private boolean warning;
    private List<PlaceHoursWarning> warnings;
    /** 조회·보강된 스냅샷 - 이후 addItem에 그대로 실어 보내면 된다 */
    private String restDateText;
    private String closeTime;
    private String useTimeText;
}
