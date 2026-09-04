package com.windmill.util;

import java.time.LocalDate;

/**
 * 당일치기 핵심 시간축. 미리 짤 때와 여행 당일을 섞지 않는다.
 * <ul>
 *   <li>계획 단계: 방문일의 요일·휴무·계획 시각 대비 마감만 본다.
 *       지금 이 순간 비·혼잡·영업종료를 미래 일정에 붙이지 않는다.</li>
 *   <li>여행 당일: 그날 단기예보·집중률로 이미 담은 일정을 미리 다시 판정한다.
 *       알림도 이날만 보낸다.</li>
 * </ul>
 */
public final class TripDayPolicy {

    private TripDayPolicy() {
    }

    public static boolean isTripDay(LocalDate visitDate) {
        return visitDate != null && visitDate.equals(KoreaClock.today());
    }

    /** 실시간 날씨·혼잡·지금 영업종료는 여행 당일에만 의미가 있다. */
    public static boolean liveConditionsApply(LocalDate visitDate) {
        return isTripDay(visitDate);
    }

    /**
     * 추천 카드 배지용. 방문일이 없으면(지금 검색) 현재 스냅샷을 쓰고,
     * 미래 방문일이면 오늘의 비·혼잡을 붙이지 않는다.
     */
    public static boolean liveSnapshotApplies(LocalDate visitDate) {
        return visitDate == null || isTripDay(visitDate);
    }
}
