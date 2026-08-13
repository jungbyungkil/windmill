package com.windmill.dto;

import com.windmill.domain.CompanionType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/** 첫 화면 입력값 - 당일치기(시작일=종료일)만 허용 */
@Data
public class CreateItineraryRequest {
    @NotBlank
    private String signguFullCode;
    @NotNull
    private LocalDate startDate;
    @NotNull
    private LocalDate endDate;
    @NotNull
    private CompanionType companionType;
    private boolean withPet;
    private boolean strollerFriendly;
    private boolean accessibleFriendly;
    /** true면 같은 날짜의 기존 진행 중 일정을 덮어쓰기(삭제 후 신규 생성) 확인한 것으로 간주 */
    private boolean force;

    @AssertTrue(message = "당일치기만 가능합니다. 여행 날짜는 하루만 선택해 주세요.")
    public boolean isDayTrip() {
        return startDate != null && endDate != null && startDate.equals(endDate);
    }

    @AssertTrue(message = "여행일은 오늘 이후여야 합니다.")
    public boolean isNotPastStart() {
        return startDate == null || !startDate.isBefore(LocalDate.now());
    }
}
