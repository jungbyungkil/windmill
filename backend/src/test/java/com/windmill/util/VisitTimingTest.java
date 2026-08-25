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
    void resolveCloseTime_paidExhibitionWithoutHours_defaultsTo20() {
        LocalTime close = VisitTiming.resolveCloseTime(null, null, 14, "디자인전시관", null, List.of("#전시"));
        assertEquals(LocalTime.of(20, 0), close);
        LocalTime byCat3 = VisitTiming.resolveCloseTime(
                null, null, 14, "DDP", null, null, "A02060300", null);
        assertEquals(LocalTime.of(20, 0), byCat3);
        assertEquals(0, VisitTiming.closeBufferMinutes(null, null));
    }

    @Test
    void resolveCloseTime_performanceWithoutHours_defaultsTo22() {
        LocalTime venue = VisitTiming.resolveCloseTime(null, null, 14, "예술의전당", "공연장", List.of("#공연장"));
        assertEquals(LocalTime.of(22, 0), venue);
        LocalTime festival = VisitTiming.resolveCloseTime(null, null, 15, "지역 음악축제", null, null);
        assertEquals(LocalTime.of(22, 0), festival);
    }

    @Test
    void resolveCloseTime_usesPlaytimeFactWhenUseTimeMissing() {
        List<com.windmill.dto.DetailFact> facts = List.of(com.windmill.dto.DetailFact.builder()
                .key("playtime").label("공연시간").value("19:00~21:00").build());
        LocalTime close = VisitTiming.resolveCloseTime(null, null, 15, "뮤지컬", null, null, null, facts);
        assertEquals(LocalTime.of(21, 0), close);
        assertTrue(VisitTiming.hasAccurateCloseTime(null, null, facts));
        assertEquals(60, VisitTiming.closeBufferMinutes(null, null, facts));
    }

    @Test
    void stayMinutes_usesSpendtimeThenExhibitionDefault() {
        List<com.windmill.dto.DetailFact> facts = List.of(com.windmill.dto.DetailFact.builder()
                .key("spendtime").label("관람소요시간").value("1시간 30분").build());
        assertEquals(90, VisitTiming.stayMinutes(14, "기획전", "전시", List.of("#전시"), "A02060300", facts));
        assertEquals(90, VisitTiming.stayMinutes(14, "유료전시", null, List.of("#전시"), "A02060300", null));
        assertEquals(120, VisitTiming.stayMinutes(15, "뮤지컬", null, null, null, null));
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
