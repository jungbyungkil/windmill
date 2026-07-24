package com.windmill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AdjustRequest {

    @NotBlank
    private String destination;

    @NotNull
    private Map<String, List<ScheduleItem>> currentSchedule;

    private String situation;
    private String weatherCondition;
    private String currentLocation;
    private String date;
}
