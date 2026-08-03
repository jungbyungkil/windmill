package com.windmill.dto;

import com.windmill.domain.CompanionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

/** 첫 화면 입력값 - 지역/날짜/동반유형/반려동물 동반 여부 */
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
}
