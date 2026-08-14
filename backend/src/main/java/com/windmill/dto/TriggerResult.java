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
    /** 휴무·영업종료 합집합 - 하위 호환용. 배지 등 구분 표시는 closedDayTrigger/hoursEndedTrigger를 우선 사용 */
    private boolean businessTrigger;
    /** 방문일이 정기휴무 요일 */
    private boolean closedDayTrigger;
    /** 정기휴무는 아니지만 방문 시각이 영업시간 밖(오늘 진행 중인 여행에만 해당) */
    private boolean hoursEndedTrigger;
    /** 동선 꼬임(비효율 경로) - 자동 재배치 제안 */
    private boolean routeTangleTrigger;
    private RouteTangleResult routeTangle;
    /** 다음 장소까지 이동시간이 부족해 마감 전 도착이 어려움 - GPS(originLon/Lat) 제공 시에만 판정 */
    private boolean travelTimeTrigger;
    private Long travelTimeAffectedItemId;
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
    /** 휴무·영업종료 합집합에 걸린 일정 - 하위 호환용 */
    private List<Long> businessAffectedItemIds;
    /** 방문일이 정기휴무 요일인 일정만 */
    private List<Long> closedDayAffectedItemIds;
    /** 영업종료(영업시간 밖)에 걸린 일정만 */
    private List<Long> hoursEndedAffectedItemIds;
    /** 혼잡에 걸린 일정만 */
    private List<Long> crowdAffectedItemIds;
    /** 여행 기간과 겹치는 지역 축제 제안 - level 산정에는 관여하지 않는다 */
    private List<FestivalSuggestion> festivalSuggestions;
}
