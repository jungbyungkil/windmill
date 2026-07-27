package com.windmill.client;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 한국관광공사 국문 관광정보 서비스 (KorService2, TourAPI 4.4).
 * ⚠ serviceKey는 data.go.kr에서 발급받은 "디코딩(Decoding)" 키를 사용할 것.
 *   WebClient가 쿼리파라미터를 자동 URL-인코딩하므로, 이미 인코딩된 키를 넣으면 이중 인코딩되어
 *   SERVICE_KEY_IS_NOT_REGISTERED_ERROR가 발생한다.
 */
@Slf4j
@Component
public class KorServiceClient {

    private static final String MOBILE_APP = "WindTrail";

    private final WebClient webClient;
    private final String serviceKey;

    public KorServiceClient(WebClient.Builder webClientBuilder,
                             @Value("${tourapi.kor-service-base-url}") String baseUrl,
                             @Value("${tourapi.key:}") String serviceKey) {
        this.webClient = webClientBuilder.clone().baseUrl(baseUrl).build();
        this.serviceKey = serviceKey;
    }

    public boolean isConfigured() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    /** 지역기반 관광정보 목록 조회 (areaBasedList2) */
    public Mono<List<JsonNode>> areaBasedList(Integer contentTypeId, String lDongRegnCd, String lDongSignguCd,
                                               int numOfRows, int pageNo, String arrange) {
        return webClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/areaBasedList2")
                            .queryParam("serviceKey", serviceKey)
                            .queryParam("numOfRows", numOfRows)
                            .queryParam("pageNo", pageNo)
                            .queryParam("MobileOS", "ETC")
                            .queryParam("MobileApp", MOBILE_APP)
                            .queryParam("_type", "json")
                            .queryParam("arrange", arrange == null ? "C" : arrange);
                    if (contentTypeId != null) {
                        uriBuilder.queryParam("contentTypeId", contentTypeId);
                    }
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
                .doOnError(e -> log.error("areaBasedList2 호출 실패: {}", e.getMessage()))
                .onErrorReturn(List.of());
    }

    /** 키워드 검색 조회 (searchKeyword2) */
    public Mono<List<JsonNode>> searchKeyword(String keyword, Integer contentTypeId,
                                               String lDongRegnCd, String lDongSignguCd,
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
                            .queryParam("keyword", keyword);
                    if (contentTypeId != null) {
                        uriBuilder.queryParam("contentTypeId", contentTypeId);
                    }
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
                .doOnError(e -> log.error("searchKeyword2 호출 실패: {}", e.getMessage()))
                .onErrorReturn(List.of());
    }

    /** 공통정보 조회 (detailCommon2) - contentId 단건 */
    public Mono<JsonNode> detailCommon(String contentId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/detailCommon2")
                        .queryParam("serviceKey", serviceKey)
                        .queryParam("numOfRows", 1)
                        .queryParam("pageNo", 1)
                        .queryParam("MobileOS", "ETC")
                        .queryParam("MobileApp", MOBILE_APP)
                        .queryParam("_type", "json")
                        .queryParam("contentId", contentId)
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .map(KtoApiResponseParser::parseItems)
                .flatMap(items -> items.isEmpty() ? Mono.empty() : Mono.just(items.get(0)))
                .doOnError(e -> log.error("detailCommon2 호출 실패: {}", e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    /** 소개정보 조회 (detailIntro2) - 타입별 응답 필드가 다름 (영업시간/휴무일 등) */
    public Mono<JsonNode> detailIntro(String contentId, int contentTypeId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/detailIntro2")
                        .queryParam("serviceKey", serviceKey)
                        .queryParam("numOfRows", 1)
                        .queryParam("pageNo", 1)
                        .queryParam("MobileOS", "ETC")
                        .queryParam("MobileApp", MOBILE_APP)
                        .queryParam("_type", "json")
                        .queryParam("contentId", contentId)
                        .queryParam("contentTypeId", contentTypeId)
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .map(KtoApiResponseParser::parseItems)
                .flatMap(items -> items.isEmpty() ? Mono.empty() : Mono.just(items.get(0)))
                .doOnError(e -> log.error("detailIntro2 호출 실패: {}", e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    /** 이미지정보 조회 (detailImage2) */
    public Mono<List<JsonNode>> detailImages(String contentId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/detailImage2")
                        .queryParam("serviceKey", serviceKey)
                        .queryParam("numOfRows", 20)
                        .queryParam("pageNo", 1)
                        .queryParam("MobileOS", "ETC")
                        .queryParam("MobileApp", MOBILE_APP)
                        .queryParam("_type", "json")
                        .queryParam("contentId", contentId)
                        .queryParam("imageYN", "Y")
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .map(KtoApiResponseParser::parseItems)
                .doOnError(e -> log.error("detailImage2 호출 실패: {}", e.getMessage()))
                .onErrorReturn(List.of());
    }

    /** 법정동 코드 조회 (ldongCode2) - lDongListYn=Y로 시도+시군구 전체 목록 조회 가능 */
    public Mono<List<JsonNode>> ldongCode(String lDongRegnCd, String lDongListYn) {
        return webClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/ldongCode2")
                            .queryParam("serviceKey", serviceKey)
                            .queryParam("numOfRows", 100)
                            .queryParam("pageNo", 1)
                            .queryParam("MobileOS", "ETC")
                            .queryParam("MobileApp", MOBILE_APP)
                            .queryParam("_type", "json")
                            .queryParam("lDongListYn", lDongListYn == null ? "N" : lDongListYn);
                    if (lDongRegnCd != null) {
                        uriBuilder.queryParam("lDongRegnCd", lDongRegnCd);
                    }
                    return uriBuilder.build();
                })
                .retrieve()
                .bodyToMono(String.class)
                .map(KtoApiResponseParser::parseItems)
                .doOnError(e -> log.error("ldongCode2 호출 실패: {}", e.getMessage()))
                .onErrorReturn(List.of());
    }
}
