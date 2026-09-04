package com.windmill.service.trigger;

import com.windmill.domain.ItineraryItem;
import com.windmill.dto.TriggerResult;
import com.windmill.util.KoreaClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriggerDetectionServiceTest {

    private final TriggerDetectionService service =
            new TriggerDetectionService(null, null, null, null, null);

    private static ItineraryItem outdoorSpot() {
        return ItineraryItem.builder()
                .placeName("설악산")
                .category("관광지")
                .contentTypeId(12)
                .indoorYn(false)
                .build();
    }

    @Test
    void futureVisitIgnoresTodaysRainAndCrowd() {
        RegionCondition raining = RegionCondition.builder()
                .crowdRateByPlaceName(Map.of("설악산", 95.0))
                .crowdRelativePercentByPlaceName(Map.of("설악산", 220.0))
                .currentPop(90.0)
                .dailyMaxTemp(36.0)
                .build();
        LocalDate future = KoreaClock.today().plusDays(5);

        TriggerResult result = service.detect(outdoorSpot(), raining, future).block();

        assertFalse(result.isWeatherTrigger(), "미리 계획할 때는 오늘 비를 그날 일정에 붙이면 안 됨");
        assertFalse(result.isHeatTrigger());
        assertFalse(result.isCrowdTrigger());
        assertFalse(result.isHoursEndedTrigger());
    }

    @Test
    void futureVisitFlagsRestDayOnThatWeekday() {
        ItineraryItem museum = ItineraryItem.builder()
                .placeName("시립박물관")
                .category("문화시설")
                .contentTypeId(14)
                .restDateText("매주 월요일")
                .build();
        LocalDate monday = LocalDate.of(2026, 9, 7);

        TriggerResult result = service.detect(museum, emptyCondition(), monday).block();

        assertTrue(result.isClosedDayTrigger(), "방문일 요일 휴무는 미리 알려야 함");
        assertFalse(result.isWeatherTrigger());
    }

    @Test
    void plannedArrivalAfterCloseIsAConflictEvenBeforeTripDay() {
        ItineraryItem item = ItineraryItem.builder()
                .placeName("전망대")
                .category("관광지")
                .scheduledTime("18:00")
                .closeTime("17:00")
                .build();

        assertTrue(TriggerDetectionService.visitConflictsWithClose(item));
        TriggerResult result = service.detect(item, emptyCondition(), KoreaClock.today().plusDays(2)).block();
        assertTrue(result.isHoursEndedTrigger(), "마감과 방문 시각이 겹치면 미리 알려야 함");
    }

    @Test
    void visitWindowIgnoresNowClosedBeforeScheduledTime() {
        ItineraryItem item = ItineraryItem.builder()
                .placeName("전망대")
                .scheduledTime("14:00")
                .closeTime("18:00")
                .build();

        assertFalse(TriggerDetectionService.visitWindowActive(item, LocalTime.of(7, 0)));
        assertTrue(TriggerDetectionService.visitWindowActive(item, LocalTime.of(14, 0)));
        assertFalse(TriggerDetectionService.visitWindowActive(item, LocalTime.of(22, 0)));
    }

    private static RegionCondition emptyCondition() {
        return RegionCondition.builder().crowdRateByPlaceName(Map.of()).build();
    }
}
