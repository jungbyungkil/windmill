package com.windmill.dto;

/** 영업 상태 - 휴무(정기휴무 요일)와 영업종료(영업시간 밖)를 구분해서 노출한다 */
public enum BusinessStatus {
    OPEN,
    /** 방문일이 정기휴무 요일 */
    CLOSED_DAY,
    /** 정기휴무는 아니지만 방문 시각이 영업시간 밖 */
    HOURS_ENDED,
}
