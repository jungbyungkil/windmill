package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class TriggerResult {
    private boolean weatherTrigger;
    private boolean crowdTrigger;
    private boolean businessTrigger;
    private int triggerCount;
    private TriggerLevel level;
    private List<String> triggerDetails;
    /** 3개 조건 중 하나라도 걸린 일정 항목의 id 목록 - 자동 재배치가 어떤 item을 교체할지 판단하는 데 사용 */
    private List<Long> affectedItemIds;
}
