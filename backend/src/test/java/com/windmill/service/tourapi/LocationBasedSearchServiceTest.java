package com.windmill.service.tourapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.windmill.client.KorServiceClient;
import com.windmill.dto.HoursPhase;
import com.windmill.dto.NearbyPlaceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocationBasedSearchServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KorServiceClient korServiceClient;
    private TourAttractionService tourAttractionService;
    private LocationBasedSearchService service;

    @BeforeEach
    void setUp() {
        korServiceClient = mock(KorServiceClient.class);
        tourAttractionService = mock(TourAttractionService.class);
        service = new LocationBasedSearchService(korServiceClient, tourAttractionService);
        when(korServiceClient.isConfigured()).thenReturn(true);
        when(tourAttractionService.peekCachedDetail(anyString())).thenReturn(null);
    }

    @Test
    void search_cacheHitDoesNotCallKorServiceTwiceForRoundedCoords() {
        when(korServiceClient.locationBasedList(anyString(), anyString(), anyInt(), isNull(), anyInt(), anyInt()))
                .thenReturn(Mono.just(List.of(item("126.978", "37.567"))));

        List<NearbyPlaceResponse> first = service.search(126.97841, 37.56651, 1000, null, 1, 40).block();
        List<NearbyPlaceResponse> second = service.search(126.97849, 37.56659, 1000, null, 1, 40).block();

        assertEquals(1, first.size());
        assertEquals("명동교자", first.get(0).getPlaceName());
        assertEquals(HoursPhase.UNKNOWN, first.get(0).getHoursPhase());
        assertEquals(first.get(0).getContentId(), second.get(0).getContentId());
        verify(korServiceClient, times(1))
                .locationBasedList(eq("126.978"), eq("37.567"), eq(1000), isNull(), eq(40), eq(1));
    }

    @Test
    void search_rejectsNonFiniteCoords() {
        assertThrows(IllegalArgumentException.class,
                () -> service.search(Double.NaN, 37.5, 1000, null, 1, 40).block());
    }

    @Test
    void search_mapsContentTypeAndDist() {
        when(korServiceClient.locationBasedList(anyString(), anyString(), anyInt(), eq(39), anyInt(), anyInt()))
                .thenReturn(Mono.just(List.of(item("126.978", "37.567"))));

        List<NearbyPlaceResponse> result = service.search(126.978, 37.567, 500, 39, 1, 20).block();

        assertEquals(1, result.size());
        assertEquals(Integer.valueOf(39), result.get(0).getContentTypeId());
        assertEquals("음식점", result.get(0).getCategory());
        assertEquals(Integer.valueOf(120), result.get(0).getDist());
    }

    private static JsonNode item(String mapX, String mapY) {
        return MAPPER.createObjectNode()
                .put("contentid", "2832391")
                .put("contenttypeid", "39")
                .put("title", "명동교자")
                .put("addr1", "서울 중구 명동10길 29")
                .put("mapx", mapX)
                .put("mapy", mapY)
                .put("firstimage", "https://example.com/a.jpg")
                .put("tel", "02-000-0000")
                .put("cat3", "A05020100")
                .put("dist", "120.4");
    }
}
