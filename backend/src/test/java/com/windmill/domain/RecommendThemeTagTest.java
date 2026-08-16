package com.windmill.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 2026-08-16 사용자 요청으로 맛집(한식/중식/일식/양식/카페), 실내(박물관/미술관/전시), 역사(고궁),
 * 액티비티(방탈출) 세부 태그를 추가 - 새 태그가 UI 태그 문자열/자연어 검색어 양쪽에서 정확히
 * 인식되고, 기존 상위 캐치올 태그(#맛집/#역사/#실내)와 서로 안 겹치는지 검증한다.
 */
class RecommendThemeTagTest {

    @Test
    void fromTag_resolvesEachNewSubTagExactly() {
        assertEquals(RecommendThemeTag.CAFE, RecommendThemeTag.fromTag("#카페"));
        assertEquals(RecommendThemeTag.KOREAN_FOOD, RecommendThemeTag.fromTag("#한식"));
        assertEquals(RecommendThemeTag.CHINESE_FOOD, RecommendThemeTag.fromTag("#중식"));
        assertEquals(RecommendThemeTag.JAPANESE_FOOD, RecommendThemeTag.fromTag("#일식"));
        assertEquals(RecommendThemeTag.WESTERN_FOOD, RecommendThemeTag.fromTag("#양식"));
        assertEquals(RecommendThemeTag.MUSEUM, RecommendThemeTag.fromTag("#박물관"));
        assertEquals(RecommendThemeTag.GALLERY, RecommendThemeTag.fromTag("#미술관"));
        assertEquals(RecommendThemeTag.EXHIBITION, RecommendThemeTag.fromTag("#전시"));
        assertEquals(RecommendThemeTag.PALACE, RecommendThemeTag.fromTag("#고궁"));
        assertEquals(RecommendThemeTag.ESCAPE_ROOM, RecommendThemeTag.fromTag("#방탈출"));
    }

    @Test
    void fromTag_subTagsDoNotCollideWithParentCatchAllTags() {
        // 세부 태그는 상위 캐치올(#맛집/#실내/#역사)로 잘못 매칭되면 안 된다
        assertEquals(RecommendThemeTag.MUSEUM, RecommendThemeTag.fromTag("#박물관"));
        assertEquals(RecommendThemeTag.KOREAN_FOOD, RecommendThemeTag.fromTag("#한식"));
        assertEquals(RecommendThemeTag.PALACE, RecommendThemeTag.fromTag("#고궁"));
        // 상위 캐치올 자체는 여전히 그대로 동작해야 한다(기존 자동 일정 생성 흐름이 이 태그들을 씀)
        assertEquals(RecommendThemeTag.FOOD, RecommendThemeTag.fromTag("#맛집"));
        assertEquals(RecommendThemeTag.INDOOR, RecommendThemeTag.fromTag("#실내"));
        assertEquals(RecommendThemeTag.HISTORY, RecommendThemeTag.fromTag("#역사"));
    }

    @Test
    void fromQuery_prefersMostSpecificSubTagOverParentCatchAll() {
        assertEquals(RecommendThemeTag.CAFE, RecommendThemeTag.fromQuery("카페 가고싶어"));
        assertEquals(RecommendThemeTag.KOREAN_FOOD, RecommendThemeTag.fromQuery("한정식 먹을 곳"));
        assertEquals(RecommendThemeTag.PALACE, RecommendThemeTag.fromQuery("경복궁 같은 고궁 가고싶어"));
        assertEquals(RecommendThemeTag.MUSEUM, RecommendThemeTag.fromQuery("박물관 데이트"));
        assertEquals(RecommendThemeTag.ESCAPE_ROOM, RecommendThemeTag.fromQuery("방탈출 하고싶어"));
        // 세부 키워드가 없는 일반 검색어는 여전히 상위 캐치올로
        assertEquals(RecommendThemeTag.FOOD, RecommendThemeTag.fromQuery("맛집 추천해줘"));
        assertEquals(RecommendThemeTag.HISTORY, RecommendThemeTag.fromQuery("역사 유적지"));
    }

    @Test
    void resolve_multipleNewTagsAllPreserved() {
        List<RecommendThemeTag> resolved = RecommendThemeTag.resolve(
                List.of("#한식", "#카페", "#박물관"), null);
        assertEquals(List.of(RecommendThemeTag.KOREAN_FOOD, RecommendThemeTag.CAFE, RecommendThemeTag.MUSEUM), resolved);
    }

    @Test
    void fromTag_unknownTagReturnsNull() {
        assertNull(RecommendThemeTag.fromTag("#존재하지않는태그"));
    }

    /**
     * 2026-08-16 - "예시 그대로 받아쓰지 말고 아이디어를 내보라"는 재요청으로 추가한 12종 -
     * 한국관광공사 categoryCode2 실제 조회로 데이터가 존재하는 카테고리만 골랐다(전통시장/온천스파/
     * 테마파크/사찰/전통체험/캠핑/등산트레킹/수상레포츠/쇼핑/공방체험/공연장/이색거리).
     */
    @Test
    void fromTag_resolvesEachOfTheTwelveIdeaTags() {
        assertEquals(RecommendThemeTag.TRADITIONAL_MARKET, RecommendThemeTag.fromTag("#전통시장"));
        assertEquals(RecommendThemeTag.HOT_SPRING, RecommendThemeTag.fromTag("#온천스파"));
        assertEquals(RecommendThemeTag.THEME_PARK, RecommendThemeTag.fromTag("#테마파크"));
        assertEquals(RecommendThemeTag.TEMPLE, RecommendThemeTag.fromTag("#사찰"));
        assertEquals(RecommendThemeTag.TRADITIONAL_EXPERIENCE, RecommendThemeTag.fromTag("#전통체험"));
        assertEquals(RecommendThemeTag.CAMPING, RecommendThemeTag.fromTag("#캠핑"));
        assertEquals(RecommendThemeTag.HIKING, RecommendThemeTag.fromTag("#등산트레킹"));
        assertEquals(RecommendThemeTag.WATER_SPORTS, RecommendThemeTag.fromTag("#수상레포츠"));
        assertEquals(RecommendThemeTag.SHOPPING, RecommendThemeTag.fromTag("#쇼핑"));
        assertEquals(RecommendThemeTag.CRAFT_WORKSHOP, RecommendThemeTag.fromTag("#공방체험"));
        assertEquals(RecommendThemeTag.PERFORMANCE_VENUE, RecommendThemeTag.fromTag("#공연장"));
        assertEquals(RecommendThemeTag.TRENDY_STREET, RecommendThemeTag.fromTag("#이색거리"));
    }

    @Test
    void fromQuery_ideaTagsPreferredOverGenericActivityCatchAll() {
        // "체험"/"레포츠" 같은 상위 캐치올 단어가 겹쳐도, 더 구체적인 새 태그가 먼저 매칭돼야 한다
        assertEquals(RecommendThemeTag.CAMPING, RecommendThemeTag.fromQuery("캠핑 체험하고 싶어"));
        assertEquals(RecommendThemeTag.TRADITIONAL_EXPERIENCE, RecommendThemeTag.fromQuery("한복 입고 전통체험"));
        assertEquals(RecommendThemeTag.CRAFT_WORKSHOP, RecommendThemeTag.fromQuery("도자기 공방 체험"));
        assertEquals(RecommendThemeTag.WATER_SPORTS, RecommendThemeTag.fromQuery("래프팅 레포츠 하고싶어"));
        assertEquals(RecommendThemeTag.HIKING, RecommendThemeTag.fromQuery("둘레길 트레킹 코스"));
        assertEquals(RecommendThemeTag.SHOPPING, RecommendThemeTag.fromQuery("아울렛 쇼핑하러 가고싶어"));
        // 새 태그와 무관한 순수 일반 액티비티 검색어는 여전히 캐치올로
        assertEquals(RecommendThemeTag.ACTIVITY, RecommendThemeTag.fromQuery("액티비티 추천해줘"));
    }
}
