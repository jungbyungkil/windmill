package com.windmill.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.client.WeatherClient;
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

    /** 기상청 단기예보 프록시 - nx/ny 미지정 시 속초 기본 격자좌표 사용은 상위 서비스(TriggerScheduler)에서 처리 */
    @GetMapping
    public Mono<ResponseEntity<List<JsonNode>>> forecast(@RequestParam String nx, @RequestParam String ny) {
        return weatherClient.getVillageForecast(nx, ny).map(ResponseEntity::ok);
    }
}
