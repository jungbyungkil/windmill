package com.windmill.dto;

/**
 * 장소 카드용 영업 3단계. CLOSED_DAY(정기휴무)는 CLOSED로 접는다.
 * 검색 목록(locationBasedList2)에는 영업시간이 없어 UNKNOWN이 기본이고,
 * 이미 캐시된 detailIntro2가 있을 때만 계산한다(추가 API 호출 없음).
 */
public enum HoursPhase {
    BEFORE_OPEN,
    OPEN,
    CLOSED,
    UNKNOWN
}
