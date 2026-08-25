package com.windmill.util;

import com.windmill.dto.DetailFact;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 값이 빈 필드·전용 UI 필드는 숨기고, 나머지 intro 키는 한글 라벨로 노출한다. */
class IntroFieldCatalogTest {

    @Test
    void foodFieldsGetKoreanLabels() {
        Map<String, String> intro = new LinkedHashMap<>();
        intro.put("firstmenu", "물회");
        intro.put("treatmenu", "회, 물회");
        intro.put("parkingfood", "가능");
        intro.put("seat", "40석");
        intro.put("packing", "가능");
        intro.put("chkcreditcardfood", "가능");
        intro.put("reservationfood", "전화 예약");
        intro.put("opendatefood", "2010-03-01");
        List<DetailFact> facts = IntroFieldCatalog.toFacts(intro);
        assertEquals(8, facts.size());
        assertEquals("대표메뉴", labelOf(facts, "firstmenu"));
        assertEquals("취급메뉴", labelOf(facts, "treatmenu"));
        assertEquals("주차", labelOf(facts, "parkingfood"));
        assertEquals("좌석수", labelOf(facts, "seat"));
        assertEquals("포장", labelOf(facts, "packing"));
        assertEquals("신용카드", labelOf(facts, "chkcreditcardfood"));
        assertEquals("예약안내", labelOf(facts, "reservationfood"));
        assertEquals("개업일", labelOf(facts, "opendatefood"));
    }

    @Test
    void blankNullAndNoneAreHidden() {
        Map<String, String> intro = Map.of(
                "firstmenu", " ",
                "treatmenu", "없음",
                "seat", "null",
                "packing", "회덮밥");
        List<DetailFact> facts = IntroFieldCatalog.toFacts(intro);
        assertEquals(1, facts.size());
        assertEquals("packing", facts.get(0).getKey());
        assertEquals("회덮밥", facts.get(0).getValue());
    }

    @Test
    void dedicatedUiKeysAreSkipped() {
        Map<String, String> intro = Map.of(
                "usetime", "09:00~18:00",
                "restdate", "매주 월요일",
                "usefee", "성인 3,000원",
                "infocenter", "033-123-4567",
                "homepage", "https://example.com",
                "overview", "소개글",
                "parking", "가능");
        List<DetailFact> facts = IntroFieldCatalog.toFacts(intro);
        assertEquals(1, facts.size());
        assertEquals("주차", facts.get(0).getLabel());
    }

    @Test
    void unknownKeysStillSurfaceWithOriginalKey() {
        List<DetailFact> facts = IntroFieldCatalog.toFacts(Map.of("brandnewfield", "값 있음"));
        assertEquals(1, facts.size());
        assertEquals("brandnewfield", facts.get(0).getLabel());
        assertEquals("값 있음", facts.get(0).getValue());
    }

    @Test
    void htmlIsStripped() {
        List<DetailFact> facts = IntroFieldCatalog.toFacts(
                Map.of("firstmenu", "<b>물회</b> &amp; 회덮밥"));
        assertEquals("물회 & 회덮밥", facts.get(0).getValue());
    }

    @Test
    void emptyMapIsEmptyList() {
        assertTrue(IntroFieldCatalog.toFacts(null).isEmpty());
        assertTrue(IntroFieldCatalog.toFacts(Map.of()).isEmpty());
    }

    private static String labelOf(List<DetailFact> facts, String key) {
        return facts.stream()
                .filter(f -> key.equals(f.getKey()))
                .findFirst()
                .orElseThrow()
                .getLabel();
    }
}
