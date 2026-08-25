package com.windmill.exception;

import com.windmill.dto.ScheduleConflictCode;
import lombok.Getter;

import java.time.LocalTime;
import java.util.List;

/** 같은 날 이미 예정된 다른 일정과 시간대가 겹칠 때 던진다 - 마감시간 게이트(ClosingTimeGate)와는 별개 원인. */
@Getter
public class TimeSlotConflictException extends RuntimeException {

    private final Long conflictingItemId;
    private final String conflictingPlaceName;
    private final LocalTime conflictingTime;
    private final List<String> suggestedTimes;

    public TimeSlotConflictException(String message, Long conflictingItemId, String conflictingPlaceName,
                                       LocalTime conflictingTime) {
        this(message, conflictingItemId, conflictingPlaceName, conflictingTime, List.of());
    }

    public TimeSlotConflictException(String message, Long conflictingItemId, String conflictingPlaceName,
                                       LocalTime conflictingTime, List<String> suggestedTimes) {
        super(message);
        this.conflictingItemId = conflictingItemId;
        this.conflictingPlaceName = conflictingPlaceName;
        this.conflictingTime = conflictingTime;
        this.suggestedTimes = suggestedTimes == null ? List.of() : List.copyOf(suggestedTimes);
    }

    public ScheduleConflictCode getErrorCode() {
        return ScheduleConflictCode.TIME_OVERLAP;
    }
}
