package com.windmill.dto;

import com.windmill.util.KoreaClock;
import com.windmill.util.SentryBreadcrumbs;

import java.time.LocalDate;

/** 일정 진행 상태 - 프론트가 매번 날짜를 직접 비교하지 않도록 서버가 판정해서 내려준다 */
public enum ItineraryStatus {
    ACTIVE, ENDED;

    /**
     * 판정 기준(KST): 이미 여행 마무리(TripRecord)를 남겼으면 무조건 종료.
     * 아니면 여행일(scheduled_date)이 오늘보다 이전이면 종료.
     */
    public static ItineraryStatus of(LocalDate startDate, boolean hasTripRecord) {
        LocalDate today = KoreaClock.today();
        SentryBreadcrumbs.timeCalc("itinerary-status",
                "startDate=" + startDate + " today(KST)=" + today + " hasTripRecord=" + hasTripRecord);
        if (hasTripRecord) {
            return ENDED;
        }
        if (startDate != null && startDate.isBefore(today)) {
            return ENDED;
        }
        return ACTIVE;
    }
}
