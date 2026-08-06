package com.windmill.controller;

import com.windmill.dto.SituationSummaryResponse;
import com.windmill.service.trigger.SituationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/situation")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SituationController {

    private final SituationService situationService;

    /** 현재 좌표 기반 상황 요약 (앱 실행 시 위치 권한으로 호출) */
    @GetMapping
    public Mono<ResponseEntity<SituationSummaryResponse>> byLocation(
            @RequestParam double lat,
            @RequestParam double lon) {
        return situationService.summarizeByLocation(lat, lon).map(ResponseEntity::ok);
    }

    /** 선택 지역 코드 기반 상황 요약 */
    @GetMapping("/region/{signguFullCode}")
    public Mono<ResponseEntity<SituationSummaryResponse>> byRegion(@PathVariable String signguFullCode) {
        return situationService.summarizeByRegion(signguFullCode).map(ResponseEntity::ok);
    }
}
