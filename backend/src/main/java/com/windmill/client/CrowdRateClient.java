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
 * 한국관광공사 관광지 집중률 방문자 추이 예측 (TatsCnctrRateService, KT 이동통신 기반).
 * cnctrRate: 해당 관광지 피크=100인 상대 집중률(0~100). 절대 방문자 수가 아님.
 * 카테고리 필드가 오면 CrowdCongestionEvaluator가 우선 사용하고,
 * 수치만 있으면 장소별 시리즈 평균 대비 %로 트리거한다.
 */
@Slf4j
@Component
public class CrowdRateClient {

    private static final String MOBILE_APP = "WindTrail";

    private final WebClient webClient;
    private final String serviceKey;

    public CrowdRateClient(WebClient.Builder webClientBuilder,
                            @Value("${tourapi.crowd-rate-base-url}") String baseUrl,
                            @Value("${tourapi.key:}") String serviceKey) {
        this.webClient = TourApiWebClientFactory.create(webClientBuilder, baseUrl);
        this.serviceKey = TourApiWebClientFactory.encode(serviceKey);
    }

    public boolean isConfigured() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    /** 관광지 집중률 정보 목록조회 (tatsCnctrRatedList) - tAtsNm 지정 시 해당 관광지의 향후 30일 추이만 반환 */
    public Mono<List<JsonNode>> crowdRateList(String areaCd, String signguCd, String tAtsNm,
                                               int numOfRows, int pageNo) {
        return webClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/tatsCnctrRatedList")
                            .queryParam("serviceKey", serviceKey)
                            .queryParam("numOfRows", numOfRows)
                            .queryParam("pageNo", pageNo)
                            .queryParam("MobileOS", "ETC")
                            .queryParam("MobileApp", MOBILE_APP)
                            .queryParam("_type", "json")
                            .queryParam("areaCd", areaCd)
                            .queryParam("signguCd", signguCd);
                    if (tAtsNm != null && !tAtsNm.isBlank()) {
                        uriBuilder.queryParam("tAtsNm", TourApiWebClientFactory.encode(tAtsNm));
                    }
                    return uriBuilder.build();
                })
                .retrieve()
                .bodyToMono(String.class)
                .map(KtoApiResponseParser::parseItems)
                .doOnError(e -> log.error("tatsCnctrRatedList 호출 실패: {}", e.getMessage()))
                .onErrorReturn(List.of());
    }
}
