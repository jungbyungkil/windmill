package com.windmill.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.util.TourApiWebClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 한국천문연구원 특일 정보(SpcdeInfoService) - 정기휴무 요일이 공휴일과 겹쳐 개관/휴관이 밀리는
 * 곳(예: "매주 월요일, 단 월요일이 공휴일이면 다음날 휴관")을 정확히 판정하려면 실제 공휴일 달력이
 * 필요해서 도입함(2026-08-16 사용자 제보 - 8/17이 대체공휴일이라 정기휴무가 아니어야 하는데
 * 요일만 보고 휴무로 오판정). tourapi.key(data.go.kr 통합 서비스키) 재사용, 별도 키 불필요.
 */
@Slf4j
@Component
public class KoreanHolidayClient {

    private static final DateTimeFormatter LOCDATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final WebClient webClient;
    private final String serviceKey;

    public KoreanHolidayClient(WebClient.Builder webClientBuilder,
                                @Value("${tourapi.holiday-base-url}") String baseUrl,
                                @Value("${tourapi.key:}") String serviceKey) {
        this.webClient = TourApiWebClientFactory.create(webClientBuilder, baseUrl);
        this.serviceKey = TourApiWebClientFactory.encode(serviceKey);
    }

    public boolean isConfigured() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    /**
     * 해당 연도의 전체 공휴일(대체공휴일 포함) 목록. getRestDeInfo가 달(solMonth) 단위로만 안정적으로
     * 반환하는 경우가 있어(연도 단독 조회 시 일부 API 버전에서 누락 사례 보고) 12개월을 나눠 조회 후
     * 합친다 - TarRlteTarService1의 baseYm 3개월 폴백과 같은 "data.go.kr 응답 불안정성 방어" 원칙.
     */
    public Mono<Set<LocalDate>> getHolidays(int year) {
        if (!isConfigured()) {
            return Mono.just(Set.of());
        }
        return Flux.range(1, 12)
                .flatMap(month -> fetchMonth(year, month), 4)
                .flatMapIterable(list -> list)
                .collect(Collectors.toSet())
                .doOnNext(dates -> log.info("[KoreanHoliday] {}년 공휴일 {}건 확보", year, dates.size()));
    }

    private Mono<List<LocalDate>> fetchMonth(int year, int month) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/getRestDeInfo")
                        .queryParam("serviceKey", serviceKey)
                        .queryParam("solYear", year)
                        .queryParam("solMonth", String.format("%02d", month))
                        .queryParam("numOfRows", 30)
                        .queryParam("_type", "json")
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .map(this::parseDates)
                .onErrorResume(e -> {
                    log.warn("[KoreanHoliday] {}년 {}월 조회 실패: {}", year, month, e.toString());
                    return Mono.just(List.of());
                });
    }

    private List<LocalDate> parseDates(String rawJson) {
        List<JsonNode> items = KtoApiResponseParser.parseItems(rawJson);
        return items.stream()
                .map(item -> item.path("locdate").asText(null))
                .filter(v -> v != null && !v.isBlank())
                .map(v -> LocalDate.parse(v, LOCDATE_FMT))
                .collect(Collectors.toList());
    }
}
