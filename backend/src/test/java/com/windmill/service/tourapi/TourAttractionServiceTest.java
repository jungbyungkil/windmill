package com.windmill.service.tourapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.windmill.client.KorServiceClient;
import com.windmill.dto.TourAttractionDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TourAttractionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KorServiceClient korServiceClient;
    private TourAttractionService service;

    @BeforeEach
    void setUp() {
        korServiceClient = mock(KorServiceClient.class);
        service = new TourAttractionService(korServiceClient);
        when(korServiceClient.detailCommon(anyString()))
                .thenReturn(Mono.just(commonNode()));
    }

    /**
     * detailIntro2 실패/빈 응답(KorServiceClient가 onErrorResume(Mono.empty())로 삼킴)이 오면
     * introFields가 빈 맵으로 내려온다 - 이걸 캐싱하면 statusAt()의 fail-open(빈 맵 → OPEN)과 맞물려
     * 최대 30분간 잘못된 "영업중"이 실제 휴무/영업종료를 덮어쓴다(2026-09-14 사용자 제보 버그).
     * 다음 호출에서 재시도해야 하므로 캐싱되면 안 된다.
     */
    @Test
    void getDetail_doesNotCacheWhenIntroFetchFails() {
        when(korServiceClient.detailIntro(anyString(), anyInt())).thenReturn(Mono.empty());

        TourAttractionDetail first = service.getDetail("12345", 14).block();
        TourAttractionDetail second = service.getDetail("12345", 14).block();

        assertTrue(first.getIntroFields().isEmpty());
        assertTrue(second.getIntroFields().isEmpty());
        verify(korServiceClient, times(2)).detailIntro(anyString(), anyInt());
        verify(korServiceClient, times(2)).detailCommon(anyString());
    }

    @Test
    void getDetail_cachesSuccessfulResponseAndSkipsSecondCall() {
        when(korServiceClient.detailIntro(anyString(), anyInt())).thenReturn(Mono.just(introNode()));

        TourAttractionDetail first = service.getDetail("12345", 14).block();
        TourAttractionDetail second = service.getDetail("12345", 14).block();

        assertEquals("매주 월요일", first.getIntroFields().get("restdate"));
        assertEquals(first.getIntroFields(), second.getIntroFields());
        verify(korServiceClient, times(1)).detailIntro(anyString(), anyInt());
        verify(korServiceClient, times(1)).detailCommon(anyString());
    }

    private static JsonNode commonNode() {
        return MAPPER.createObjectNode()
                .put("title", "부엉이전시관")
                .put("addr1", "강원 속초시");
    }

    private static JsonNode introNode() {
        return MAPPER.createObjectNode()
                .put("restdate", "매주 월요일")
                .put("usetime", "09:00~18:00");
    }
}
