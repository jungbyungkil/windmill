package com.windmill.util;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeConflictGateTest {

    @Test
    void overlappingIntervals_areBlockedWithPlaceNameInMessage() {
        List<TimeConflictGate.Occupant> occupants = List.of(
                new TimeConflictGate.Occupant(1L, "세종뮤지엄갤러리", LocalTime.of(11, 30), LocalTime.of(12, 45)));

        TimeConflictGate.CheckResult result = TimeConflictGate.check(
                LocalTime.of(11, 30), LocalTime.of(12, 45), occupants, null);

        assertTrue(result.blocked());
        assertEquals(1L, result.conflictingItemId());
        assertEquals("세종뮤지엄갤러리", result.conflictingPlaceName());
        assertTrue(result.message().contains("세종뮤지엄갤러리"));
    }

    @Test
    void touchingEndToStart_isNotOverlap() {
        List<TimeConflictGate.Occupant> occupants = List.of(
                new TimeConflictGate.Occupant(1L, "경복궁", LocalTime.of(11, 0), LocalTime.of(12, 15)));

        TimeConflictGate.CheckResult result = TimeConflictGate.check(
                LocalTime.of(12, 15), LocalTime.of(13, 30), occupants, null);

        assertFalse(result.blocked());
        assertTrue(result.allowed());
    }

    @Test
    void afternoonStart_doesNotConflictWithMorningLastStop() {
        List<TimeConflictGate.Occupant> occupants = List.of(
                new TimeConflictGate.Occupant(1L, "아쿠아플라넷 제주", LocalTime.of(9, 0), LocalTime.of(9, 22)),
                new TimeConflictGate.Occupant(3L, "가시식당", LocalTime.of(11, 5), LocalTime.of(11, 25)));

        TimeConflictGate.CheckResult result = TimeConflictGate.check(
                LocalTime.of(14, 0), LocalTime.of(14, 22), occupants, 2L);

        assertFalse(result.blocked());
        assertTrue(result.allowed());
    }

    @Test
    void excludeItemId_doesNotConflictWithSelf() {
        List<TimeConflictGate.Occupant> occupants = List.of(
                new TimeConflictGate.Occupant(1L, "경복궁", LocalTime.of(17, 0), LocalTime.of(18, 15)));

        TimeConflictGate.CheckResult result = TimeConflictGate.check(
                LocalTime.of(17, 0), LocalTime.of(18, 15), occupants, 1L);

        assertFalse(result.blocked());
    }
}
