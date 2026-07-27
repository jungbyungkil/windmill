package com.windmill.client;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 한국관광공사 관광지별 연관 관광지 정보 서비스 (TarRlteTarService1).
 * ⚠ 이 서비스는 KorService2와 다른 구(舊) 지역코드 체계(areaCd/signguCd)를 쓰고,
 *   응답의 tAtsCd/rlteTatsCd는 KorService2의 contentId와 무관한 자체 코드다.
 *   KorService2와 조인하려면 rlteTatsNm(연관관광지명) 문자열을 title과 매칭해야 한다.
 */
@Slf4j
@Component
public class RelatedAttractionClient {

    private static final String MOBILE_APP = "WindTrail";
    private static final DateTimeFormatter YM_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    private final WebClient webClient;
    private final String serviceKey;

    public RelatedAttractionClient(WebClient.Builder webClientBuilder,
                                    @Value("${tourapi.related-attraction-base-url}") String baseUrl,
                                    @Value("${tourapi.key:}") String serviceKey) {
        this.webClient = webClientBuilder.clone().baseUrl(baseUrl).build();
        this.serviceKey = serviceKey;
    }

    public boolean isConfigured() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    /** 지역기반 관광지별 연관 관광지 조회 (areaBasedList1) - 기준연월은 최신월 사용 */
    public Mono<List<JsonNode>> areaBasedRelated(String areaCd, String signguCd, int numOfRows, int pageNo) {
        String baseYm = LocalDate.now().format(YM_FORMAT);
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/areaBasedList1")
                        .queryParam("serviceKey", serviceKey)
                        .queryParam("numOfRows", numOfRows)
                        .queryParam("pageNo", pageNo)
                        .queryParam("MobileOS", "ETC")
                        .queryParam("MobileApp", MOBILE_APP)
                        .queryParam("_type", "json")
                        .queryParam("baseYm", baseYm)
                        .queryParam("areaCd", areaCd)
                        .queryParam("signguCd", signguCd)
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .map(KtoApiResponseParser::parseItems)
                .doOnError(e -> log.error("areaBasedList1(연관관광지) 호출 실패: {}", e.getMessage()))
                .onErrorReturn(List.of());
    }

    /** 키워드검색 관광지별 연관 관광지 조회 (searchKeyword1) */
    public Mono<List<JsonNode>> searchKeywordRelated(String areaCd, String signguCd, String keyword,
                                                      int numOfRows, int pageNo) {
        String baseYm = LocalDate.now().format(YM_FORMAT);
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/searchKeyword1")
                        .queryParam("serviceKey", serviceKey)
                        .queryParam("numOfRows", numOfRows)
                        .queryParam("pageNo", pageNo)
                        .queryParam("MobileOS", "ETC")
                        .queryParam("MobileApp", MOBILE_APP)
                        .queryParam("_type", "json")
                        .queryParam("baseYm", baseYm)
                        .queryParam("areaCd", areaCd)
                        .queryParam("signguCd", signguCd)
                        .queryParam("keyword", keyword)
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .map(KtoApiResponseParser::parseItems)
                .doOnError(e -> log.error("searchKeyword1(연관관광지) 호출 실패: {}", e.getMessage()))
                .onErrorReturn(List.of());
    }
}
