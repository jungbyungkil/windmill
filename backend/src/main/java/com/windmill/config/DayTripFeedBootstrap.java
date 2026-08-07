package com.windmill.config;

import com.windmill.service.trip.DevSeedService;
import com.windmill.service.trip.TripRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 기동 시 다일 이력을 정리하고, 당일치기 추천 피드가 비어 있으면 샘플을 채운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DayTripFeedBootstrap implements ApplicationRunner {

    private final TripRecordService tripRecordService;
    private final DevSeedService devSeedService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int removed = tripRecordService.clearMultiDayRecords();
            boolean seeded = devSeedService.seedIfEmpty();
            log.info("[DayTripFeed] bootstrap done - removedMultiDay={}, seeded={}", removed, seeded);
        } catch (Exception e) {
            log.warn("[DayTripFeed] bootstrap skipped: {}", e.getMessage());
        }
    }
}
