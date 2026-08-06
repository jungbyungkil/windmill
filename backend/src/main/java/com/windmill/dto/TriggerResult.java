package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class TriggerResult {
    private boolean weatherTrigger;
    /** 폭염 + 야외 일정 조합 - 실내 전환 권고. 바람개비 경고(빨강)에 반영 */
    private boolean heatTrigger;
    private boolean crowdTrigger;
    private boolean businessTrigger;
    private int triggerCount;
    private TriggerLevel level;
    private List<String> triggerDetails;
    /** 조건에 걸린 일정 항목의 id 목록 - 자동 재배치가 어떤 item을 교체할지 판단하는 데 사용 */
    private List<Long> affectedItemIds;
    /** 여행 기간과 겹치는 지역 축제 제안 - "문제"가 아니라 "기회"라 level 산정에는 관여하지 않는다 */
    private List<FestivalSuggestion> festivalSuggestions;
}
