package com.windmill.exception;

import com.windmill.dto.ScheduleConflictCode;
import lombok.Getter;

import java.time.LocalTime;
import java.util.List;

/** 도착 시각이 장소 마감(임박 버퍼 포함)을 넘길 때. TIME_OVERLAP과 동시에 해당되면 겹침을 먼저 던진다. */
@Getter
public class ClosingTimeInfeasibleException extends RuntimeException {

    private final LocalTime closeTime;
    private final LocalTime latestArrivalBy;
    private final List<String> suggestedTimes;

    public ClosingTimeInfeasibleException(String message, LocalTime closeTime, LocalTime latestArrivalBy,
                                          List<String> suggestedTimes) {
        super(message);
        this.closeTime = closeTime;
        this.latestArrivalBy = latestArrivalBy;
        this.suggestedTimes = suggestedTimes == null ? List.of() : List.copyOf(suggestedTimes);
    }

    public ScheduleConflictCode getErrorCode() {
        return ScheduleConflictCode.CLOSING_TIME_INFEASIBLE;
    }
}
