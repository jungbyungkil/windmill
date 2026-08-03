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
 * 한국관광공사 빅데이터 지역별 방문자수 서비스 (DataLabService).
 * MVP 4단계 파이프라인에는 사용하지 않음 - 참고용 컨텍스트(지역 혼잡 배경 정보) 표시 용도의 보조 데이터.
 */
@Slf4j
@Component
public class VisitorStatsClient {

    private static final String MOBILE_APP = "WindTrail";

    private final WebClient webClient;
    private final String serviceKey;

    public VisitorStatsClient(WebClient.Builder webClientBuilder,
                               @Value("${tourapi.visitor-stats-base-url}") String baseUrl,
                               @Value("${tourapi.key:}") String serviceKey) {
        this.webClient = TourApiWebClientFactory.create(webClientBuilder, baseUrl);
        this.serviceKey = TourApiWebClientFactory.encode(serviceKey);
    }

    public boolean isConfigured() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    /** 광역 지자체 지역방문자수 집계 데이터 조회 (metcoRegnVisitrDDList) */
    public Mono<List<JsonNode>> metroRegionVisitors(String startYmd, String endYmd, int numOfRows, int pageNo) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/metcoRegnVisitrDDList")
                        .queryParam("serviceKey", serviceKey)
                        .queryParam("numOfRows", numOfRows)
                        .queryParam("pageNo", pageNo)
                        .queryParam("MobileOS", "ETC")
                        .queryParam("MobileApp", MOBILE_APP)
                        .queryParam("_type", "json")
                        .queryParam("startYmd", startYmd)
                        .queryParam("endYmd", endYmd)
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .map(KtoApiResponseParser::parseItems)
                .doOnError(e -> log.error("metcoRegnVisitrDDList 호출 실패: {}", e.getMessage()))
                .onErrorReturn(List.of());
    }
}
