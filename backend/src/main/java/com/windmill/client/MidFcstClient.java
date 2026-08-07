package com.windmill.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.util.TourApiWebClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 기상청 중기예보 (MidFcstInfoService) - 육상·기온.
 * 발표 06/18시, 대략 3~10일 앞 전망. tourapi.key(통합 서비스키) 재사용.
 */
@Slf4j
@Component
public class MidFcstClient {

    private static final DateTimeFormatter TM_FC = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final WebClient webClient;
    private final String serviceKey;

    public MidFcstClient(WebClient.Builder webClientBuilder,
                         @Value("${weather.mid.base-url}") String baseUrl,
                         @Value("${tourapi.key:}") String serviceKey) {
        this.webClient = TourApiWebClientFactory.create(webClientBuilder, baseUrl);
        this.serviceKey = TourApiWebClientFactory.encode(serviceKey);
    }

    public boolean isConfigured() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    public Mono<List<JsonNode>> getMidLandFcst(String landRegId) {
        return get("/getMidLandFcst", landRegId);
    }

    public Mono<List<JsonNode>> getMidTa(String taRegId) {
        return get("/getMidTa", taRegId);
    }

    private Mono<List<JsonNode>> get(String path, String regId) {
        if (!isConfigured() || regId == null || regId.isBlank()) {
            return Mono.just(List.of());
        }
        String tmFc = latestTmFc(LocalDateTime.now());
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path(path)
                        .queryParam("serviceKey", serviceKey)
                        .queryParam("numOfRows", 10)
                        .queryParam("pageNo", 1)
                        .queryParam("dataType", "JSON")
                        .queryParam("regId", regId)
                        .queryParam("tmFc", tmFc)
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .map(KtoApiResponseParser::parseItems)
                .doOnError(e -> log.error("{} 호출 실패 (regId={}): {}", path, regId, e.getMessage()))
                .onErrorReturn(List.of());
    }

    /** 중기예보 발표 06시/18시 - 반영 여유 약 1시간 */
    public static String latestTmFc(LocalDateTime now) {
        LocalDateTime safe = now.minusHours(1);
        int hour = safe.getHour();
        if (hour < 6) {
            return safe.minusDays(1).withHour(18).withMinute(0).withSecond(0).withNano(0).format(TM_FC);
        }
        if (hour < 18) {
            return safe.withHour(6).withMinute(0).withSecond(0).withNano(0).format(TM_FC);
        }
        return safe.withHour(18).withMinute(0).withSecond(0).withNano(0).format(TM_FC);
    }
}
