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
    /** TMX ≥ 35℃(경보 프록시) */
    private boolean heatUrgent;
    private boolean crowdTrigger;
    /** 평시 대비 200%·매우붐빔 등 — 바람개비 DANGER 승격 */
    private boolean crowdUrgent;
    private boolean businessTrigger;
    /** 동선 꼬임(비효율 경로) - 자동 재배치 제안 */
    private boolean routeTangleTrigger;
    private RouteTangleResult routeTangle;
    private int triggerCount;
    private TriggerLevel level;
    private List<String> triggerDetails;
    /**
     * 조건에 걸린 일정 항목 id (날씨·폭염·혼잡·휴무 합집합).
     * 하위 호환용 — UI는 weather/business/crowd 목록을 우선 사용.
     */
    private List<Long> affectedItemIds;
    /** 비·폭염에 걸린 야외 일정만 */
    private List<Long> weatherAffectedItemIds;
    /** 휴무·영업종료에 걸린 일정만 */
    private List<Long> businessAffectedItemIds;
    /** 혼잡에 걸린 일정만 */
    private List<Long> crowdAffectedItemIds;
    /** 여행 기간과 겹치는 지역 축제 제안 - level 산정에는 관여하지 않는다 */
    private List<FestivalSuggestion> festivalSuggestions;
}
