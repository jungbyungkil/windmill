package com.windmill.domain;

/** 상황 태그 출처. MANUAL이면 자동 추론이 덮어쓰지 않는다. */
public enum InferredSource {
    RULE,
    MANUAL
}
