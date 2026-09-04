package com.windmill.service.recommendation;

import com.windmill.dto.BusinessStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Stage2BusinessHoursFilterTest {

    @Test
    void futureVisitUsesRestDayNotTheClockNow() {
        Map<String, String> fields = Map.of(
                "usetime", "09:00~18:00",
                "restdate", "매주 월요일");

        LocalDate tuesday = LocalDate.of(2026, 9, 8);
        assertEquals(BusinessStatus.OPEN, Stage2BusinessHoursFilter.resolveStatusForVisit(fields, tuesday));

        LocalDate monday = LocalDate.of(2026, 9, 7);
        assertEquals(BusinessStatus.CLOSED_DAY, Stage2BusinessHoursFilter.resolveStatusForVisit(fields, monday));
    }
}
