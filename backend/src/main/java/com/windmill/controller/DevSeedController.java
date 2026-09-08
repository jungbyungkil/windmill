package com.windmill.controller;

import com.windmill.service.trip.DevSeedService;
import com.windmill.service.trip.ScheduleSnapMigrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final ScheduleSnapMigrationService scheduleSnapMigrationService;

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

    /**
     * 일회성 마이그레이션 - 완료된 여행 기록의 방문 시각을 30분 단위로 스냅(중복·역전은 +30분).
     * confirm 없이 호출하면 dry-run(변경 건수만 리턴), {@code ?confirm=true}면 실제 UPDATE.
     * 활성(진행 중) 일정은 대상이 아니다.
     */
    @PostMapping("/snap-schedule-times")
    public Mono<ResponseEntity<Map<String, Object>>> snapScheduleTimes(
            @RequestParam(defaultValue = "false") boolean confirm) {
        return Mono.fromCallable(() -> scheduleSnapMigrationService.run(confirm))
                .subscribeOn(Schedulers.boundedElastic())
                .map(r -> ResponseEntity.ok(Map.<String, Object>of(
                        "applied", r.applied(),
                        "tripRecords", r.tripRecords(),
                        "recordsProcessed", r.recordsProcessed(),
                        "itemsScanned", r.itemsScanned(),
                        "itemsChanged", r.itemsChanged(),
                        "reversalsAfter", r.reversalsAfter(),
                        "message", r.applied()
                                ? "완료된 여행 기록 일정 시각을 30분 단위로 스냅했습니다."
                                : "dry-run 결과입니다. ?confirm=true 로 실제 반영하세요."
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
