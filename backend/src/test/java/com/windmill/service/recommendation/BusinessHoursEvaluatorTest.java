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
    void secondAndFourthWednesdayCloses_wordOrdinal() {
        String rest = "매월 두번째·네번째 수요일";
        assertTrue(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 12))); // 2주차
        assertTrue(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 26))); // 4주차
        assertFalse(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 5)));  // 1주차
        assertFalse(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 19))); // 3주차
        // 요일이 다르면(일요일) 주차가 맞아도 닫지 않음
        assertFalse(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 16)));
    }

    @Test
    void secondAndFourthWednesdayCloses_numericOrdinal() {
        String rest = "매월 2,4주 수요일";
        assertTrue(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 12)));
        assertTrue(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 26)));
        assertFalse(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 5)));
    }

    @Test
    void maedalSynonymForMaewolWithMultipleOrdinals() {
        // 실제 사례(성식당): "매달"(매월 동의어) + 여러 주차 나열 - 2026-08-13은 8월의 2번째 목요일이라
        // "셋째·넷째·다섯째"에 안 걸려야 함(매주 목요일로 오인해 휴무 처리하면 안 됨)
        String rest = "매달 셋째, 넷째, 다섯째 목요일";
        assertFalse(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 13))); // 2번째 목요일
        assertTrue(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 20)));  // 3번째 목요일
    }

    @Test
    void firstTuesdayOfMonthCloses() {
        String rest = "매월 첫째주 화요일";
        assertTrue(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 4)));
        assertFalse(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 11)));
    }

    @Test
    void bareWeekdayNoPrefixCloses() {
        // 실제 TourAPI 사례(전원미술관): restdate 필드가 "매주" 없이 "월요일"만 있는 경우
        String rest = "월요일";
        assertTrue(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 17))); // 월요일
        assertFalse(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 18))); // 화요일
    }

    @Test
    void bareWeekdayFallback_doesNotOverrideNthWeekRule() {
        // "매월"이 포함된 텍스트는 3)단계(bare weekday) 대상이 아니라 1.5)단계 주차 판정을 따라야 함
        String rest = "매월 두번째·네번째 수요일";
        assertFalse(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 5))); // 1주차 수요일
        assertTrue(BusinessHoursEvaluator.isClosedOnRestDate(rest, LocalDate.of(2026, 8, 12))); // 2주차 수요일
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
