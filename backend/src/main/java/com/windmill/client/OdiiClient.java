package com.windmill.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.util.TourApiWebClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 한국관광공사 관광지 오디오 가이드정보 서비스 (오디/Odii).
 * 실제 사람이 만든 음성 스크립트(script)+오디오 파일(audioUrl)을 관광지명 키워드로 검색해 제공한다.
 * DocentScriptService가 이 공식 콘텐츠를 우선 사용하고, 없을 때만 OpenAI로 폴백한다.
 * ⚠ data.go.kr에서 별도 활용신청 승인이 필요하다 (2026-08-03 기준 SERVICE_KEY_IS_NOT_REGISTERED_ERROR 확인).
 */
@Slf4j
@Component
public class OdiiClient {

    private static final String MOBILE_APP = "WindTrail";

    private final WebClient webClient;
    private final String serviceKey;

    public OdiiClient(WebClient.Builder webClientBuilder,
                       @Value("${tourapi.odii-base-url}") String baseUrl,
                       @Value("${tourapi.key:}") String serviceKey) {
        this.webClient = TourApiWebClientFactory.create(webClientBuilder, baseUrl);
        this.serviceKey = TourApiWebClientFactory.encode(serviceKey);
    }

    public boolean isConfigured() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    /** 이야기(오디오 가이드) 키워드검색 (storySearchList) - 장소명으로 검색해 script/audioUrl을 얻는다 */
    public Mono<List<JsonNode>> searchStory(String keyword, String langCode) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/storySearchList")
                        .queryParam("serviceKey", serviceKey)
                        .queryParam("numOfRows", 1)
                        .queryParam("pageNo", 1)
                        .queryParam("MobileOS", "ETC")
                        .queryParam("MobileApp", MOBILE_APP)
                        .queryParam("_type", "json")
                        .queryParam("keyword", TourApiWebClientFactory.encode(keyword))
                        .queryParam("langCode", langCode == null ? "ko" : langCode)
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .map(KtoApiResponseParser::parseItems)
                .doOnError(e -> log.error("Odii storySearchList 호출 실패: {}", e.getMessage()))
                .onErrorReturn(List.of());
    }
}
