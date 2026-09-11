package com.windmill.dto;

import lombok.Data;

/** 지난 일정(완료) 수동 오버라이드 요청. true=완료 표시, false=다시 진행 중으로 되돌림. */
@Data
public class ItemCompletionRequest {
    private boolean completed;
}
