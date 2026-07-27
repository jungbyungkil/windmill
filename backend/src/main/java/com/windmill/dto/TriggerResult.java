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
}
