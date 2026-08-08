package com.windmill.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/** 추천 여행 기록의 일정을 그대로 복제해 새 당일치기를 시작할 때 사용 */
@Data
public class StartFromTripRecordRequest {
    @NotNull
    private LocalDate startDate;

    @AssertTrue(message = "여행일은 오늘 이후여야 합니다.")
    public boolean isNotPastStart() {
        return startDate == null || !startDate.isBefore(LocalDate.now());
    }
}
