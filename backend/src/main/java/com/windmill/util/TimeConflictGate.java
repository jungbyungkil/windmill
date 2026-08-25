package com.windmill.util;

import java.time.LocalTime;
import java.util.List;

/**
 * 같은 날 이미 예정된 다른 일정과 시간대가 겹치는지 검사(마감시간 게이트와 독립적인 원인).
 *
 * <p>비교는 같은 날짜 슬롯끼리만. 점유 구간은 scheduledTime ~ occupancyEnd(체류, 마감으로 캡).
 * 겹침 공식: {@code (A.시작 < B.종료) AND (A.종료 > B.시작)}. 이동시간은 포함하지 않는다.
 *
 * <p>시각은 KST 벽시계 {@link LocalTime}. UTC Instant와 비교하지 않는다(폐점일 UTC 버그와 동일 패턴).
 */
public final class TimeConflictGate {

    /** @deprecated VisitTiming.ATTRACTION_STAY_MINUTES 를 쓰세요. 기존 호출 호환용. */
    public static final int STAY_MINUTES = VisitTiming.ATTRACTION_STAY_MINUTES;

    private TimeConflictGate() {
    }

    public static CheckResult check(LocalTime candidateStart, LocalTime candidateEnd,
                                    List<Occupant> occupants, Long excludeItemId) {
        if (candidateStart == null) {
            return CheckResult.noConflict();
        }
        LocalTime end = candidateEnd != null
                ? candidateEnd
                : candidateStart.plusMinutes(VisitTiming.ATTRACTION_STAY_MINUTES);
        for (Occupant o : occupants) {
            if (o.start() == null) {
                continue;
            }
            if (excludeItemId != null && excludeItemId.equals(o.itemId())) {
                continue;
            }
            LocalTime occupantEnd = o.end() != null
                    ? o.end()
                    : o.start().plusMinutes(VisitTiming.ATTRACTION_STAY_MINUTES);
            if (VisitTiming.overlaps(candidateStart, end, o.start(), occupantEnd)) {
                String message = String.format("%s에 이미 다른 일정(%s)이 있어요.",
                        ClosingTimeGate.formatFriendly(o.start()), o.placeName());
                return new CheckResult(false, true, o.itemId(), o.placeName(), o.start(), message);
            }
        }
        return CheckResult.noConflict();
    }

    public static CheckResult check(LocalTime candidateStart, List<Occupant> occupants, Long excludeItemId) {
        return check(candidateStart, null, occupants, excludeItemId);
    }

    public record Occupant(Long itemId, String placeName, LocalTime start, LocalTime end) {
        public Occupant(Long itemId, String placeName, LocalTime start) {
            this(itemId, placeName, start, start == null ? null : start.plusMinutes(STAY_MINUTES));
        }
    }

    public record CheckResult(
            boolean allowed,
            boolean blocked,
            Long conflictingItemId,
            String conflictingPlaceName,
            LocalTime conflictingTime,
            String message
    ) {
        static CheckResult noConflict() {
            return new CheckResult(true, false, null, null, null, null);
        }
    }
}
