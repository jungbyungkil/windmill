package com.windmill.service.notification;

import com.windmill.dto.TriggerLevel;
import com.windmill.dto.TriggerResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlertIconResolverTest {

    @Test
    void weatherTakesPriorityOverEverythingElse() {
        TriggerResult result = TriggerResult.builder()
                .weatherTrigger(true).crowdTrigger(true).level(TriggerLevel.DANGER).build();
        assertEquals("🌧️", AlertIconResolver.resolve(result));
    }

    @Test
    void heatWinsOverCrowdAndBusiness() {
        TriggerResult result = TriggerResult.builder()
                .heatTrigger(true).crowdTrigger(true).closedDayTrigger(true).level(TriggerLevel.DANGER).build();
        assertEquals("🌡️", AlertIconResolver.resolve(result));
    }

    @Test
    void crowdWinsOverBusinessAndRoute() {
        TriggerResult result = TriggerResult.builder()
                .crowdTrigger(true).hoursEndedTrigger(true).routeTangleTrigger(true).level(TriggerLevel.WARNING).build();
        assertEquals("👥", AlertIconResolver.resolve(result));
    }

    @Test
    void closedDayBeforeHoursEndedBeforeRouteBeforeTravelTime() {
        assertEquals("🚫", AlertIconResolver.resolve(TriggerResult.builder()
                .closedDayTrigger(true).hoursEndedTrigger(true).routeTangleTrigger(true).travelTimeTrigger(true)
                .level(TriggerLevel.WARNING).build()));
        assertEquals("🕐", AlertIconResolver.resolve(TriggerResult.builder()
                .hoursEndedTrigger(true).routeTangleTrigger(true).travelTimeTrigger(true)
                .level(TriggerLevel.WARNING).build()));
        assertEquals("🔀", AlertIconResolver.resolve(TriggerResult.builder()
                .routeTangleTrigger(true).travelTimeTrigger(true).level(TriggerLevel.WARNING).build()));
        assertEquals("🚗", AlertIconResolver.resolve(TriggerResult.builder()
                .travelTimeTrigger(true).level(TriggerLevel.WARNING).build()));
    }

    @Test
    void fallsBackToLevelEmojiWhenNoCauseIsSet() {
        assertEquals("🟢", AlertIconResolver.resolve(TriggerResult.builder().level(TriggerLevel.NORMAL).build()));
        assertEquals("🟡", AlertIconResolver.resolve(TriggerResult.builder().level(TriggerLevel.WARNING).build()));
        assertEquals("🔴", AlertIconResolver.resolve(TriggerResult.builder().level(TriggerLevel.DANGER).build()));
    }

    @Test
    void nullResultFallsBackToNormal() {
        assertEquals("🟢", AlertIconResolver.resolve(null));
    }
}
