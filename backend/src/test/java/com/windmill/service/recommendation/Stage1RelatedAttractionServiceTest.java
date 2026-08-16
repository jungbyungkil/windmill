package com.windmill.service.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.windmill.client.KakaoLocalSearchClient;
import com.windmill.client.KorServiceClient;
import com.windmill.client.PetFriendlyAttractionClient;
import com.windmill.client.RelatedAttractionClient;
import com.windmill.dto.RegionCode;
import com.windmill.dto.RelatedCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "이름으로 검색"(앵커 등록)이 카카오맵 검색을 우선 쓰고, 결과가 없을 때만 기존 TourAPI 캐스케이드로
 * 폴백하는지 + 선택한 카카오 후보를 시/군/구→시/도→전국 순으로 넓혀가며 TourAPI와 이름 매칭하는지
 * 검증 (2026-08-16 사용자 요청 - "중앙시장" 검색 시 TourAPI 자체 검색이 카카오맵보다 훨씬 부실했음).
 */
class Stage1RelatedAttractionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RelatedAttractionClient relatedAttractionClient;
    private KorServiceClient korServiceClient;
    private PetFriendlyAttractionClient petFriendlyAttractionClient;
    private KakaoLocalSearchClient kakaoLocalSearchClient;
    private Stage1RelatedAttractionService service;
    private RegionCode region;

    @BeforeEach
    void setUp() {
        relatedAttractionClient = mock(RelatedAttractionClient.class);
        korServiceClient = mock(KorServiceClient.class);
        petFriendlyAttractionClient = mock(PetFriendlyAttractionClient.class);
        kakaoLocalSearchClient = mock(KakaoLocalSearchClient.class);
        service = new Stage1RelatedAttractionService(
                relatedAttractionClient, korServiceClient, petFriendlyAttractionClient, kakaoLocalSearchClient);
        region = RegionCode.builder()
                .sidoName("강원특별자치도").signguName("속초시")
                .signguFullCode("51210").lDongRegnCd("51").lDongSignguCd("51210")
                .build();
    }

    private static JsonNode korItem(String contentId, String contentTypeId) {
        return MAPPER.createObjectNode()
                .put("title", "속초중앙시장")
                .put("contentid", contentId)
                .put("contenttypeid", contentTypeId)
                .put("mapx", "128.5918")
                .put("mapy", "38.2058");
    }

    @Test
    void searchByNameViaKakao_usesKakaoResultsAndSkipsTourApiCascade() {
        RelatedCandidate kakaoHit = RelatedCandidate.builder().placeName("속초중앙시장").addr1("강원 속초시 중앙동").build();
        when(kakaoLocalSearchClient.searchKeyword(anyString())).thenReturn(Mono.just(List.of(kakaoHit)));

        List<RelatedCandidate> result = service.searchByNameViaKakao(region, "중앙시장").block();

        assertEquals(List.of(kakaoHit), result);
        verify(korServiceClient, never()).searchKeyword(anyString(), any(), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void searchByNameViaKakao_fallsBackToTourApiCascadeWhenKakaoEmpty() {
        when(kakaoLocalSearchClient.searchKeyword(anyString())).thenReturn(Mono.just(List.of()));
        when(korServiceClient.searchKeyword(eq("중앙시장"), isNull(), eq("51"), eq("51210"), anyInt(), anyInt()))
                .thenReturn(Mono.just(List.of(korItem("126508", "12"))));

        List<RelatedCandidate> result = service.searchByNameViaKakao(region, "중앙시장").block();

        assertEquals(1, result.size());
        assertEquals("126508", result.get(0).getContentId());
    }

    @Test
    void resolveByNameCascading_widensFromSignguToSidoToNationwideUntilMatchFound() {
        RelatedCandidate picked = RelatedCandidate.builder().placeName("속초중앙시장").mapX("128.591").mapY("38.205").build();
        when(korServiceClient.searchKeyword(eq("속초중앙시장"), isNull(), eq("51"), eq("51210"), eq(1), eq(1)))
                .thenReturn(Mono.just(List.of()));
        when(korServiceClient.searchKeyword(eq("속초중앙시장"), isNull(), eq("51"), isNull(), eq(1), eq(1)))
                .thenReturn(Mono.just(List.of()));
        when(korServiceClient.searchKeyword(eq("속초중앙시장"), isNull(), isNull(), isNull(), eq(1), eq(1)))
                .thenReturn(Mono.just(List.of(korItem("126508", "12"))));

        RelatedCandidate resolved = service.resolveByNameCascading(picked, region).block();

        assertEquals("126508", resolved.getContentId());
        assertEquals(12, resolved.getContentTypeId());
        // 카카오가 이미 준 좌표는 유지된다(narrow-scope 매칭 실패해도 원본 후보 객체를 계속 사용)
        assertEquals("128.591", resolved.getMapX());
        verify(korServiceClient, times(3))
                .searchKeyword(eq("속초중앙시장"), isNull(), any(), any(), eq(1), eq(1));
    }

    @Test
    void resolveByNameCascading_noMatchAnywhere_leavesContentIdNull() {
        RelatedCandidate picked = RelatedCandidate.builder().placeName("존재하지않는곳").build();
        when(korServiceClient.searchKeyword(eq("존재하지않는곳"), isNull(), any(), any(), eq(1), eq(1)))
                .thenReturn(Mono.just(List.of()));

        RelatedCandidate resolved = service.resolveByNameCascading(picked, region).block();

        assertNull(resolved.getContentId());
    }
}
