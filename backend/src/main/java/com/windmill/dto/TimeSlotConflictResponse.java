package com.windmill.dto;

import com.windmill.exception.TimeSlotConflictException;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/** 409 응답 본문 - 시간대 겹침(마감시간 게이트와 무관한 별도 원인)을 프론트에 구조적으로 알려준다. */
@Data
@Builder
public class TimeSlotConflictResponse {
    private ScheduleConflictCode errorCode;
    private Long conflictingItemId;
    private String conflictingPlaceName;
    private String conflictingTime;
    private String message;
    private List<String> suggestedTimes;

    public static TimeSlotConflictResponse from(TimeSlotConflictException e) {
        return TimeSlotConflictResponse.builder()
                .errorCode(ScheduleConflictCode.TIME_OVERLAP)
                .conflictingItemId(e.getConflictingItemId())
                .conflictingPlaceName(e.getConflictingPlaceName())
                .conflictingTime(e.getConflictingTime() == null ? null : e.getConflictingTime().toString())
                .message(e.getMessage())
                .suggestedTimes(e.getSuggestedTimes())
                .build();
    }
}
