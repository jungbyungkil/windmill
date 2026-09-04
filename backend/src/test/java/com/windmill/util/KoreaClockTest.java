package com.windmill.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KoreaClockTest {

    @Test
    void toUtcWall_convertsKstAfternoonToUtcMorning() {
        LocalDateTime kst = LocalDateTime.of(2026, 9, 4, 14, 25);
        assertEquals(LocalDateTime.of(2026, 9, 4, 5, 25), KoreaClock.toUtcWall(kst));
    }

    @Test
    void toKstOffset_convertsUtcWallToSeoulOffset() {
        LocalDateTime utcWall = LocalDateTime.of(2026, 9, 4, 5, 25);
        var offset = KoreaClock.toKstOffset(utcWall);
        assertEquals(LocalDateTime.of(2026, 9, 4, 14, 25), offset.toLocalDateTime());
        assertEquals(ZoneOffset.ofHours(9), offset.getOffset());
    }

    @Test
    void roundTrip_kstNowSurvivesUtcStorage() {
        LocalDateTime kst = LocalDateTime.of(2026, 9, 4, 11, 25);
        assertEquals(kst, KoreaClock.toKstOffset(KoreaClock.toUtcWall(kst)).toLocalDateTime());
    }
}
