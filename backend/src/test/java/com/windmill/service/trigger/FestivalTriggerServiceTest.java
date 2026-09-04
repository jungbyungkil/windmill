package com.windmill.service.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.windmill.client.KorServiceClient;
import com.windmill.client.TourApiAreaCodes;
import com.windmill.dto.FestivalSuggestion;
import com.windmill.dto.RegionCode;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FestivalTriggerServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RegionCode BUSAN = RegionCode.builder()
            .sidoName("부산광역시")
            .signguName("해운대구")
            .signguFullCode("26350")
            .lDongRegnCd("26")
            .lDongSignguCd("350")
            .build();
    private static final LocalDate TRIP_START = LocalDate.of(2026, 8, 24);
    private static final LocalDate TRIP_END = LocalDate.of(2026, 8, 24);

    @Test
    void tourApiAreaCode_mapsSeoulAndBusan() {
        assertEquals("1", TourApiAreaCodes.fromLDongRegnCd("11"));
        assertEquals("6", TourApiAreaCodes.fromLDongRegnCd("26"));
    }

    @Test
    void matchesRegion_acceptsTourApiAreaCodeWhenLDongMissing() {
        RegionCode seoul = RegionCode.builder()
                .sidoName("서울특별시")
                .lDongRegnCd("11")
                .build();
        ObjectNode jongno = MAPPER.createObjectNode()
                .put("title", "종로축제")
                .put("areacode", "1")
                .put("addr1", "종로구 사직로 161");

        assertTrue(FestivalTriggerService.matchesRegion(jongno, seoul));
        assertFalse(FestivalTriggerService.matchesRegion(jongno, BUSAN));
    }
        JsonNode busanByCode = festival("부산불꽃축제", "26", "부산광역시 수영구");
        JsonNode seoulByCode = festival("서울거리공연", "11", "서울특별시 종로구 효자로13길 45");
        JsonNode busanByAddr = festival("광안리축제", null, "부산광역시 수영구 광안동");
        JsonNode seoulByAddr = festival("국악공연 진연", null, "서울특별시 종로구 인사동5길 10");

        assertTrue(FestivalTriggerService.matchesRegion(busanByCode, BUSAN));
        assertFalse(FestivalTriggerService.matchesRegion(seoulByCode, BUSAN));
        assertTrue(FestivalTriggerService.matchesRegion(busanByAddr, BUSAN));
        assertFalse(FestivalTriggerService.matchesRegion(seoulByAddr, BUSAN));
    }

    @Test
    void filterAndMap_doesNotSuggestSeoulWhenTripIsBusan() {
        List<JsonNode> mixed = List.of(
                festival("팔색찬란", "11", "서울특별시 종로구 효자로13길 45"),
                festival("국악공연 진연", "11", "서울특별시 종로구 인사동5길 10"),
                festival("부산바다축제", "26", "부산광역시 해운대구 중동")
        );

        List<FestivalSuggestion> result = FestivalTriggerService.filterAndMap(mixed, BUSAN, TRIP_START, TRIP_END);

        assertEquals(List.of("부산바다축제"), result.stream().map(FestivalSuggestion::getPlaceName).toList());
    }

    @Test
    void findDuringTrip_fallsBackToAreaListWhenSearchReturnsOtherRegions() {
        KorServiceClient kor = mock(KorServiceClient.class);
        FestivalTriggerService service = new FestivalTriggerService(kor);
        JsonNode seoul = festival("서울거리공연", "11", "서울특별시 종로구 세종대로");
        JsonNode busan = festival("부산바다축제", "26", "부산광역시 해운대구 중동");

        when(kor.searchFestival(anyString(), eq("6"), anyInt(), eq(1)))
                .thenReturn(Mono.just(List.of(seoul)));
        when(kor.searchFestival(anyString(), eq("6"), anyInt(), eq(2)))
                .thenReturn(Mono.just(List.of()));
        when(kor.areaBasedList(eq(FestivalTriggerService.FESTIVAL_CONTENT_TYPE_ID), eq("26"), isNull(),
                anyInt(), anyInt(), anyString()))
                .thenReturn(Mono.just(List.of(busan)));
        when(kor.detailCommon(anyString())).thenReturn(Mono.empty());

        List<FestivalSuggestion> result = service.findDuringTrip(BUSAN, TRIP_START, TRIP_END).block();

        assertEquals(List.of("부산바다축제"), result.stream().map(FestivalSuggestion::getPlaceName).toList());
        verify(kor).areaBasedList(eq(FestivalTriggerService.FESTIVAL_CONTENT_TYPE_ID), eq("26"), isNull(),
                anyInt(), anyInt(), anyString());
    }

    @Test
    void findDuringTrip_queriesTourApiAreaCodeNotLDong() {
        KorServiceClient kor = mock(KorServiceClient.class);
        FestivalTriggerService service = new FestivalTriggerService(kor);
        JsonNode busan = festival("부산바다축제", "26", "부산광역시 중구");

        when(kor.searchFestival(anyString(), eq("6"), anyInt(), anyInt()))
                .thenReturn(Mono.just(List.of(busan)));
        when(kor.detailCommon(anyString())).thenReturn(Mono.empty());

        service.findDuringTrip(BUSAN, TRIP_START, TRIP_END).block();

        verify(kor).searchFestival(eq("20260824"), eq("6"), eq(100), eq(1));
    }

    @Test
    void copyEventDates_fillsMissingPeriodFromIntro() {
        ObjectNode item = MAPPER.createObjectNode()
                .put("contentid", "1")
                .put("title", "거리공연");
        ObjectNode intro = MAPPER.createObjectNode()
                .put("eventstartdate", "20260901")
                .put("eventenddate", "20260910");

        JsonNode merged = FestivalTriggerService.copyEventDates(item, intro);

        assertEquals("20260901", merged.path("eventstartdate").asText());
        assertEquals("20260910", merged.path("eventenddate").asText());
    }

    private static ObjectNode festival(String title, String lDongRegnCd, String addr1) {
        ObjectNode node = MAPPER.createObjectNode()
                .put("contentid", title)
                .put("contenttypeid", "15")
                .put("title", title)
                .put("addr1", addr1)
                .put("eventstartdate", "20260820")
                .put("eventenddate", "20260830");
        if (lDongRegnCd != null) {
            node.put("ldongregncd", lDongRegnCd);
        }
        return node;
    }
}
