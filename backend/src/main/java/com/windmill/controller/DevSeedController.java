package com.windmill.controller;

import com.windmill.service.trip.DevSeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/** 로컬/개발 전용 - @Profile("!prod")라 Render(prod) 배포에서는 이 컨트롤러 자체가 등록되지 않는다 */
@RestController
@RequestMapping("/api/dev")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Profile("!prod")
public class DevSeedController {

    private final DevSeedService devSeedService;

    @PostMapping("/seed-trip-records")
    public Mono<ResponseEntity<Map<String, Object>>> seedTripRecords() {
        return Mono.fromCallable(devSeedService::seedIfEmpty)
                .subscribeOn(Schedulers.boundedElastic())
                .map(seeded -> ResponseEntity.ok(Map.of(
                        "seeded", seeded,
                        "message", seeded ? "속초 샘플 여행기록 5건을 생성했습니다." : "이미 충분한 기록이 있어 건너뛰었습니다."
                )));
    }
}
