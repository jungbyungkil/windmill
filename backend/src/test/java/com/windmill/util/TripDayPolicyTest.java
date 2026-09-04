package com.windmill.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripDayPolicyTest {

    @Test
    void liveConditionsOnlyOnTripDay() {
        assertTrue(TripDayPolicy.liveConditionsApply(KoreaClock.today()));
        assertFalse(TripDayPolicy.liveConditionsApply(KoreaClock.today().plusDays(1)));
        assertFalse(TripDayPolicy.liveConditionsApply(null));
    }

    @Test
    void liveSnapshotTreatsMissingDateAsNowSearch() {
        assertTrue(TripDayPolicy.liveSnapshotApplies(null));
        assertTrue(TripDayPolicy.liveSnapshotApplies(KoreaClock.today()));
        assertFalse(TripDayPolicy.liveSnapshotApplies(KoreaClock.today().plusDays(2)));
    }

    @Test
    void isTripDayRequiresExactCalendarDate() {
        assertFalse(TripDayPolicy.isTripDay(null));
        assertTrue(TripDayPolicy.isTripDay(KoreaClock.today()));
        assertFalse(TripDayPolicy.isTripDay(KoreaClock.today().plusDays(1)));
    }
}
