package com.windmill.dto;

/** 일정 편집 UI 게이트 — 4단계 파이프라인과 별개로 사용자가 만든 충돌을 막는다. */
public enum ScheduleConflictCode {
    /** 같은 날 다른 슬롯과 시간대가 겹침 */
    TIME_OVERLAP,
    /** 해당 장소 영업 마감(또는 마감 임박 버퍼) 안에 도착할 수 없음 */
    CLOSING_TIME_INFEASIBLE
}
