package com.windmill.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.client.WeatherClient;
import com.windmill.dto.MidTermForecastResponse;
import com.windmill.service.weather.MidTermForecastService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/weather")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class WeatherController {

    private final WeatherClient weatherClient;
    private final MidTermForecastService midTermForecastService;

    /** 기상청 단기예보 프록시 (~3일) */
    @GetMapping
    public Mono<ResponseEntity<List<JsonNode>>> forecast(@RequestParam String nx, @RequestParam String ny) {
        return weatherClient.getVillageForecast(nx, ny).map(ResponseEntity::ok);
    }

    /** 기상청 중기예보 요약 (~3~10일) - 시군구 코드로 구역 매핑 */
    @GetMapping("/mid")
    public Mono<ResponseEntity<MidTermForecastResponse>> mid(@RequestParam String signguFullCode) {
        return midTermForecastService.bySigngu(signguFullCode).map(ResponseEntity::ok);
    }
}
