package com.windmill.dto;

import lombok.Data;

/**
 * 되돌리기 요청 - {@code targetSequence}가 null이면 원본으로, 아니면 그 번호의 변경 이력으로.
 * 프론트는 확인 모달을 거친 뒤 호출한다. 되돌리기 자체도 새 변경 이력(triggerType=REVERT)으로 남는다.
 */
@Data
public class RevertPlanRequest {
    private Integer targetSequence;
}
