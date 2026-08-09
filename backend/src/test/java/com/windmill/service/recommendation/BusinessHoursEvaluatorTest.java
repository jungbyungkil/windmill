package com.windmill.service.recommendation;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessHoursEvaluatorTest {

    @Test
    void lastSundayOfMonthOnly_notEverySunday() {
        String rest = "매월 마지막 주 일요일 / 공휴일 (단, 일요일 제외) / 임시휴관일";
        // 2026-08-09 = 일요일, 8월 마지막 일요일은 30일
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
}
