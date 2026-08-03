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
 * 한국관광공사 반려동물 동반여행 서비스 (KorPetTourService2).
 * KorService2와 동일한 지역코드(lDongRegnCd/lDongSignguCd) 체계 및 응답 스키마(contentid/firstimage 등)를 쓴다.
 * ⚠ data.go.kr에서 이 서비스는 KorService2/TarRlteTarService1과 별개로 활용신청 승인이 필요하다
 *   (2026-08-03 기준 SERVICE_KEY_IS_NOT_REGISTERED_ERROR 확인 - 마이페이지에서 승인 상태 확인 필요).
 */
@Slf4j
@Component
public class PetFriendlyAttractionClient {

    private static final String MOBILE_APP = "WindTrail";

    private final WebClient webClient;
    private final String serviceKey;

    public PetFriendlyAttractionClient(WebClient.Builder webClientBuilder,
                                        @Value("${tourapi.pet-friendly-base-url}") String baseUrl,
                                        @Value("${tourapi.key:}") String serviceKey) {
        this.webClient = TourApiWebClientFactory.create(webClientBuilder, baseUrl);
        this.serviceKey = TourApiWebClientFactory.encode(serviceKey);
    }

    public boolean isConfigured() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    /** 반려동물 동반 가능 관광지 지역기반 목록 조회 (areaBasedList2) */
    public Mono<List<JsonNode>> areaBasedList(String lDongRegnCd, String lDongSignguCd, int numOfRows, int pageNo) {
        return webClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/areaBasedList2")
                            .queryParam("serviceKey", serviceKey)
                            .queryParam("numOfRows", numOfRows)
                            .queryParam("pageNo", pageNo)
                            .queryParam("MobileOS", "ETC")
                            .queryParam("MobileApp", MOBILE_APP)
                            .queryParam("_type", "json")
                            .queryParam("arrange", "C");
                    if (lDongRegnCd != null) {
                        uriBuilder.queryParam("lDongRegnCd", lDongRegnCd);
                    }
                    if (lDongSignguCd != null) {
                        uriBuilder.queryParam("lDongSignguCd", lDongSignguCd);
                    }
                    return uriBuilder.build();
                })
                .retrieve()
                .bodyToMono(String.class)
                .map(KtoApiResponseParser::parseItems)
                .doOnError(e -> log.error("반려동물동반 areaBasedList2 호출 실패: {}", e.getMessage()))
                .onErrorReturn(List.of());
    }

    /** 반려동물 동반 가능 관광지 키워드검색 (searchKeyword2) */
    public Mono<List<JsonNode>> searchKeyword(String keyword, String lDongRegnCd, String lDongSignguCd,
                                               int numOfRows, int pageNo) {
        return webClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/searchKeyword2")
                            .queryParam("serviceKey", serviceKey)
                            .queryParam("numOfRows", numOfRows)
                            .queryParam("pageNo", pageNo)
                            .queryParam("MobileOS", "ETC")
                            .queryParam("MobileApp", MOBILE_APP)
                            .queryParam("_type", "json")
                            .queryParam("arrange", "C")
                            .queryParam("keyword", TourApiWebClientFactory.encode(keyword));
                    if (lDongRegnCd != null) {
                        uriBuilder.queryParam("lDongRegnCd", lDongRegnCd);
                    }
                    if (lDongSignguCd != null) {
                        uriBuilder.queryParam("lDongSignguCd", lDongSignguCd);
                    }
                    return uriBuilder.build();
                })
                .retrieve()
                .bodyToMono(String.class)
                .map(KtoApiResponseParser::parseItems)
                .doOnError(e -> log.error("반려동물동반 searchKeyword2 호출 실패: {}", e.getMessage()))
                .onErrorReturn(List.of());
    }
}
