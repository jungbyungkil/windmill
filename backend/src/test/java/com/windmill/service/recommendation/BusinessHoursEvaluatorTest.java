package com.windmill.service.recommendation;

import com.windmill.util.ClosingTimeGate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessHoursEvaluatorTest {

    @Test
    void lastSundayOfMonthOnly_notEverySunday() {
        String rest = "매월 마지막 주 일요일 / 공휴일 (단, 일요일 제외) / 임시휴관일";
        assertFalse(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 9)));
        assertTrue(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 30)));
        assertFalse(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 10)));
    }

    @Test
    void everySundayCloses() {
        String rest = "매주 일요일 / 공휴일";
        assertTrue(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 9)));
        assertFalse(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 10)));
    }

    @Test
    void yearRoundOpen() {
        assertFalse(BusinessHoursEvaluator.isClosedOnRestDate("연중무휴", LocalDate.of(2026, 8, 9)));
    }

    @Test
    void extractCloseTimeFromUseTime() {
        LocalTime close = BusinessHoursEvaluator.extractCloseTimeFromText("09:00 ~ 17:00");
        assertEquals(LocalTime.of(17, 0), close);
        assertEquals("17:00", BusinessHoursEvaluator.formatHhMm(close));
    }

    @Test
    void closingGateBlocksAtCloseAndWithinBuffer() {
        LocalTime close = LocalTime.of(17, 0);
        ClosingTimeGate.CheckResult atClose = ClosingTimeGate.check(close, LocalTime.of(17, 0));
        assertTrue(atClose.blocked());
        assertTrue(atClose.message().contains("17시"));
        assertTrue(atClose.message().contains("16시"));

        ClosingTimeGate.CheckResult atBuffer = ClosingTimeGate.check(close, LocalTime.of(16, 0));
        assertTrue(atBuffer.blocked());

        ClosingTimeGate.CheckResult early = ClosingTimeGate.check(close, LocalTime.of(15, 30));
        assertFalse(early.blocked());
        assertTrue(early.allowed());
    }

    @Test
    void extractCloseFromIntroMap() {
        LocalTime close = BusinessHoursEvaluator.extractCloseTime(
                Map.of("usetimeculture", "10:00 ~ 18:00 (입장마감 17:00)"));
        assertEquals(LocalTime.of(18, 0), close);
    }

    @Test
    void missingCloseAllows() {
        assertNull(BusinessHoursEvaluator.extractCloseTimeFromText("상시개방"));
        assertFalse(ClosingTimeGate.check(null, LocalTime.of(17, 0)).blocked());
    }
}
