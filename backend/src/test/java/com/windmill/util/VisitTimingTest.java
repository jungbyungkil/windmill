package com.windmill.util;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisitTimingTest {

    @Test
    void resolveCloseTime_usesParsedCloseWhenAccurate() {
        LocalTime close = VisitTiming.resolveCloseTime("16:50", null, 14, "우표박물관", null, null);
        assertEquals(LocalTime.of(16, 50), close);
    }

    @Test
    void resolveCloseTime_parsesUseTimeTextWhenCloseSnapshotMissing() {
        LocalTime close = VisitTiming.resolveCloseTime(null, "09:00 ~ 18:00", 12, "남산공원", null, null);
        assertEquals(LocalTime.of(18, 0), close);
    }

    @Test
    void resolveCloseTime_museumWithoutHours_defaultsTo17() {
        LocalTime close = VisitTiming.resolveCloseTime(null, null, 14, "시립박물관", null, null);
        assertEquals(LocalTime.of(17, 0), close);
        LocalTime byName = VisitTiming.resolveCloseTime(null, null, 12, "세종뮤지엄갤러리", "전시", null);
        assertEquals(LocalTime.of(17, 0), byName);
    }

    @Test
    void resolveCloseTime_otherSlotWithoutHours_defaultsTo18() {
        LocalTime close = VisitTiming.resolveCloseTime(null, null, 12, "남산타워", null, null);
        assertEquals(LocalTime.of(18, 0), close);
        assertFalse(VisitTiming.hasAccurateCloseTime(null, null));
        assertEquals(0, VisitTiming.closeBufferMinutes(null, null));
    }

    @Test
    void resolveCloseTime_mealWithoutHours_defaultsTo21() {
        LocalTime close = VisitTiming.resolveCloseTime(null, null, 39, "한식당", "맛집", List.of("#맛집"));
        assertEquals(LocalTime.of(21, 0), close);
    }

    @Test
    void occupancyEnd_capsStayAtClose() {
        LocalTime end = VisitTiming.occupancyEnd(LocalTime.of(16, 0), 75, LocalTime.of(17, 0));
        assertEquals(LocalTime.of(17, 0), end);
    }

    @Test
    void overlaps_standardIntervalFormula_touchingEndsDoNotOverlap() {
        assertTrue(VisitTiming.overlaps(
                LocalTime.of(11, 30), LocalTime.of(12, 45),
                LocalTime.of(12, 0), LocalTime.of(13, 15)));
        assertFalse(VisitTiming.overlaps(
                LocalTime.of(11, 30), LocalTime.of(12, 45),
                LocalTime.of(12, 45), LocalTime.of(14, 0)));
    }

    @Test
    void suggestAlternativeStarts_prefersTimesAfterTheRequestedSlot() {
        List<VisitTiming.Occupied> occupied = List.of(new VisitTiming.Occupied(
                1L, "세종뮤지엄갤러리", LocalTime.of(11, 30), LocalTime.of(12, 45)));
        List<String> times = VisitTiming.suggestAlternativeStarts(
                LocalTime.of(11, 30), LocalTime.of(18, 0), 75, occupied, LocalTime.of(9, 0), null);
        assertFalse(times.isEmpty());
        assertEquals("12:45", times.get(0));
        assertTrue(times.size() <= 3);
    }
}
