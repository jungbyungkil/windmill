package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class TravelPlanResponse {
    private String destination;
    private String startDate;
    private String endDate;
    private int travelers;
    private Map<String, List<ScheduleItem>> dailySchedule;
    private WeatherInfo weather;
    private String aiSummary;
}
