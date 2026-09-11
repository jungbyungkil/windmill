package com.windmill.util;

import com.windmill.domain.ItineraryItem;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 일정 항목의 "지난 일정(완료)" 판정. 시각 비교는 전부 KST 벽시계({@link KoreaClock}).
 *
 * <p>완료된 항목은 pinwheel 상태 산출·영업시간 재평가·혼잡도 폴링에서 제외돼(TriggerDetectionService)
 * KorService2 등 일일 호출 한도를 아낀다. 사용자가 되돌리면({@code completionOverride="ACTIVE"})
 * 시각이 지났어도 다시 진행 중으로 본다.
 *
 * @see ItineraryItem#getCompletionOverride()
 */
public final class ItineraryItemStatus {

    public static final String OVERRIDE_DONE = "DONE";
    public static final String OVERRIDE_ACTIVE = "ACTIVE";

    private ItineraryItemStatus() {
    }

    /**
     * @param visitDate 항목 방문일 (null이면 자동완료 판정 안 함)
     * @param today     KST 오늘
     * @param now       KST 현재 시각
     */
    public static boolean isCompleted(ItineraryItem item, LocalDate visitDate, LocalDate today, LocalTime now) {
        if (item == null) {
            return false;
        }
        String override = item.getCompletionOverride();
        if (OVERRIDE_DONE.equals(override)) {
            return true;
        }
        if (OVERRIDE_ACTIVE.equals(override)) {
            return false;
        }
        if (visitDate == null) {
            return false;
        }
        if (visitDate.isBefore(today)) {
            return true;
        }
        if (visitDate.isAfter(today)) {
            return false;
        }
        LocalTime end = VisitTiming.occupancyEnd(item);
        return end != null && now.isAfter(end);
    }

    /** KST 지금 기준. */
    public static boolean isCompletedNow(ItineraryItem item, LocalDate visitDate) {
        return isCompleted(item, visitDate, KoreaClock.today(), KoreaClock.nowTime());
    }
}
