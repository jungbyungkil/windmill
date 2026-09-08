package com.windmill.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 대안 채택 통합 요청 - 삭제 + 추가(+ 선택적 동선 재계산)를 서버에서 한 트랜잭션으로 처리하고
 * 변경 이력을 한 건만 남긴다. 프론트가 deleteItem→addItem→optimizeRoute를 나눠 부르던 것을 대체.
 */
@Data
public class ApplyAlternativeRequest {

    /** 대안으로 교체될(삭제될) 기존 항목 id. null이면 순수 추가로 처리. */
    private Long removedItemId;

    /** 새로 담을 대안 장소. isAlternate는 서버가 강제로 true로 세팅한다. */
    @NotNull
    @Valid
    private AddItineraryItemRequest newPlace;

    /** WEATHER | HEAT | CROWD | ROUTE | MANUAL - 변경 이력 triggerType */
    private String triggerType;

    /** 사람이 읽는 변경 사유. 없으면 triggerType 기준 기본 문구. */
    private String reason;

    /** 교체 후 카카오 이동시간 기준 동선을 다시 잡을지 */
    private boolean reoptimize;
}
