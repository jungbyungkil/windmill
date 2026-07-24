package com.windmill.controller;

import com.windmill.dto.*;
import com.windmill.service.ClaudeService;
import com.windmill.service.TourApiService;
import com.windmill.service.WeatherService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TravelController {

    private final ClaudeService claudeService;
    private final WeatherService weatherService;
    private final TourApiService tourApiService;

    @PostMapping("/schedule")
    public ResponseEntity<TravelPlanResponse> createSchedule(@Valid @RequestBody TravelPlanRequest request) {
        Map<String, List<ScheduleItem>> dailySchedule = claudeService.generateSchedule(request);
        WeatherInfo weather = weatherService.getWeather(request.getDestination());

        int days = (int) ChronoUnit.DAYS.between(request.getStartDate(), request.getEndDate()) + 1;
        String summary = claudeService.generateAiSummary(request.getDestination(), days);

        TravelPlanResponse response = TravelPlanResponse.builder()
                .destination(request.getDestination())
                .startDate(request.getStartDate().toString())
                .endDate(request.getEndDate().toString())
                .travelers(request.getTravelers())
                .dailySchedule(dailySchedule)
                .weather(weather)
                .aiSummary(summary)
                .build();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/schedule/adjust")
    public ResponseEntity<TravelPlanResponse> adjustSchedule(@Valid @RequestBody AdjustRequest request) {
        Map<String, List<ScheduleItem>> adjusted = claudeService.adjustSchedule(request);
        WeatherInfo weather = weatherService.getWeather(request.getDestination());

        TravelPlanResponse response = TravelPlanResponse.builder()
                .destination(request.getDestination())
                .dailySchedule(adjusted)
                .weather(weather)
                .aiSummary("날씨/혼잡도 변수를 반영하여 일정이 재조정되었습니다.")
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/weather")
    public ResponseEntity<WeatherInfo> getWeather(@RequestParam String location) {
        return ResponseEntity.ok(weatherService.getWeather(location));
    }

    @GetMapping("/attractions")
    public ResponseEntity<List<TourApiService.Attraction>> getAttractions(@RequestParam String region) {
        return ResponseEntity.ok(tourApiService.getAttractions(region));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "우리 여기로 가자"));
    }
}
