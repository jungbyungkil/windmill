package com.windmill.controller;

import com.windmill.service.trip.DevSeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/** 당일치기 피드 시드/초기화 */
@RestController
@RequestMapping("/api/dev")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DevSeedController {

    private final DevSeedService devSeedService;

    @PostMapping("/seed-trip-records")
    public Mono<ResponseEntity<Map<String, Object>>> seedTripRecords() {
        return Mono.fromCallable(devSeedService::seedIfEmpty)
                .subscribeOn(Schedulers.boundedElastic())
                .map(seeded -> ResponseEntity.ok(Map.of(
                        "seeded", seeded,
                        "message", seeded
                                ? "속초 당일치기 샘플 여행기록 5건을 생성했습니다."
                                : "이미 충분한 당일치기 기록이 있어 건너뛰었습니다."
                )));
    }

    /** 다일 이력 삭제 + 당일치기 추천 피드로 재구성 */
    @PostMapping("/reset-daytrip-feed")
    public Mono<ResponseEntity<Map<String, Object>>> resetDayTripFeed() {
        return Mono.fromCallable(devSeedService::resetToDayTripFeed)
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> ResponseEntity.ok(Map.<String, Object>of(
                        "removedMultiDay", result.removedMultiDay(),
                        "seeded", result.seeded(),
                        "dayTripCount", result.dayTripCount(),
                        "message", "당일치기 추천 피드로 초기화했습니다."
                )));
    }
}
