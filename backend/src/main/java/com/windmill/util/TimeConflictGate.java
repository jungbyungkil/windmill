package com.windmill.util;

import java.time.LocalTime;
import java.util.List;

/**
 * 같은 날 이미 예정된 다른 일정과 시간대가 겹치는지 검사(마감시간 게이트와 독립적인 원인).
 * 각 일정은 scheduledTime부터 기본 체류시간(STAY_MINUTES)만큼 자리를 차지한다고 본다.
 */
public final class TimeConflictGate {

    public static final int STAY_MINUTES = 75;

    private TimeConflictGate() {
    }

    public static CheckResult check(LocalTime candidateStart, List<Occupant> occupants, Long excludeItemId) {
        if (candidateStart == null) {
            return CheckResult.noConflict();
        }
        LocalTime candidateEnd = candidateStart.plusMinutes(STAY_MINUTES);
        for (Occupant o : occupants) {
            if (o.start() == null) {
                continue;
            }
            if (excludeItemId != null && excludeItemId.equals(o.itemId())) {
                continue;
            }
            LocalTime occupantEnd = o.start().plusMinutes(STAY_MINUTES);
            boolean overlaps = candidateStart.isBefore(occupantEnd) && o.start().isBefore(candidateEnd);
            if (overlaps) {
                String message = String.format("%s에 이미 다른 일정(%s)이 있어요.",
                        ClosingTimeGate.formatFriendly(o.start()), o.placeName());
                return new CheckResult(false, true, o.itemId(), o.placeName(), o.start(), message);
            }
        }
        return CheckResult.noConflict();
    }

    public record Occupant(Long itemId, String placeName, LocalTime start) {
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
