package com.windmill.controller;

import com.windmill.service.trip.DevSeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * 알림 피드 화면 검증용 - TOURAPI_KEY 없는 로컬에서 실제 트리거 없이 샘플 알림 이력을 심는다.
 * 클래스 레벨 @Profile("!prod")로 완전히 분리(기존 DevSeedController 엔드포인트들은 안 건드림,
 * @Profile은 메서드 단위로는 적용되지 않아 클래스를 분리해야 함).
 */
@Profile("!prod")
@RestController
@RequestMapping("/api/dev")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AlertFeedDevSeedController {

    private final DevSeedService devSeedService;

    @PostMapping("/seed-alert-events")
    public Mono<ResponseEntity<Map<String, Object>>> seedAlertEvents(@RequestParam Long itineraryId) {
        return Mono.fromCallable(() -> devSeedService.seedAlertEvents(itineraryId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(count -> ResponseEntity.ok(Map.of(
                        "seeded", count,
                        "message", "알림 샘플 " + count + "건을 생성했습니다."
                )));
    }
}
