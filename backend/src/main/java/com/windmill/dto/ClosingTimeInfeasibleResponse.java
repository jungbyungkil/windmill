package com.windmill.dto;

import com.windmill.exception.ClosingTimeInfeasibleException;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 422 응답 본문 - 영업 마감으로 해당 시각에 넣을 수 없음. TIME_OVERLAP과 동시에면 겹침이 우선. */
@Data
@Builder
public class ClosingTimeInfeasibleResponse {
    private ScheduleConflictCode errorCode;
    private String closeTime;
    private String latestArrivalBy;
    private String message;
    private List<String> suggestedTimes;

    public static ClosingTimeInfeasibleResponse from(ClosingTimeInfeasibleException e) {
        return ClosingTimeInfeasibleResponse.builder()
                .errorCode(ScheduleConflictCode.CLOSING_TIME_INFEASIBLE)
                .closeTime(e.getCloseTime() == null ? null : e.getCloseTime().toString())
                .latestArrivalBy(e.getLatestArrivalBy() == null ? null : e.getLatestArrivalBy().toString())
                .message(e.getMessage())
                .suggestedTimes(e.getSuggestedTimes())
                .build();
    }
}
