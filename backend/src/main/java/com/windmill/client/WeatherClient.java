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
 * 기상청 단기예보 조회서비스 (VilageFcstInfoService_2.0, getVilageFcst).
 * 3시간 간격 발표(02,05,08,11,14,17,20,23시), 5km 격자(nx,ny) 기준.
 * ⚠ nx/ny는 위경도가 아닌 기상청 격자좌표 - 지역별 변환은 기상청 공식 변환표로 확정 필요.
 *
 * data.go.kr은 1인당 서비스키 1개로 활용신청한 모든 공공데이터포털 OpenAPI(관광공사/기상청 포함)를
 * 이용할 수 있으므로, 별도 WEATHER_API_KEY 없이 tourapi.key(TOURAPI_KEY)를 그대로 재사용한다.
 */
@Slf4j
@Component
public class WeatherClient {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int[] ANNOUNCE_HOURS = {2, 5, 8, 11, 14, 17, 20, 23};
    /** 발표 후 API 반영까지의 지연 여유시간(분) - 이 시간이 지나야 해당 회차 데이터가 안정적으로 조회됨 */
    private static final int PUBLISH_DELAY_MINUTES = 40;

    private final WebClient webClient;
    private final String serviceKey;

    public WeatherClient(WebClient.Builder webClientBuilder,
                          @Value("${weather.api.base-url}") String baseUrl,
                          @Value("${tourapi.key:}") String serviceKey) {
        this.webClient = TourApiWebClientFactory.create(webClientBuilder, baseUrl);
        this.serviceKey = TourApiWebClientFactory.encode(serviceKey);
    }

    public boolean isConfigured() {
        return serviceKey != null && !serviceKey.isBlank();
    }

    /** 가장 최근 발표된 단기예보 회차 기준 예보 목록 조회 */
    public Mono<List<JsonNode>> getVillageForecast(String nx, String ny) {
        LocalDateTime base = latestAnnouncedBaseTime(LocalDateTime.now());
        String baseDate = base.format(DATE_FORMAT);
        String baseTime = String.format("%02d00", base.getHour());
        return getVillageForecast(nx, ny, baseDate, baseTime);
    }

    public Mono<List<JsonNode>> getVillageForecast(String nx, String ny, String baseDate, String baseTime) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/getVilageFcst")
                        .queryParam("serviceKey", serviceKey)
                        .queryParam("numOfRows", 1000)
                        .queryParam("pageNo", 1)
                        .queryParam("dataType", "JSON")
                        .queryParam("base_date", baseDate)
                        .queryParam("base_time", baseTime)
                        .queryParam("nx", nx)
                        .queryParam("ny", ny)
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .map(KtoApiResponseParser::parseItems)
                .doOnError(e -> log.error("getVilageFcst 호출 실패: {}", e.getMessage()))
                .onErrorReturn(List.of());
    }

    private LocalDateTime latestAnnouncedBaseTime(LocalDateTime now) {
        LocalDateTime safe = now.minusMinutes(PUBLISH_DELAY_MINUTES);
        int hour = safe.getHour();
        int chosen = ANNOUNCE_HOURS[0];
        LocalDateTime result = safe;
        boolean found = false;
        for (int i = ANNOUNCE_HOURS.length - 1; i >= 0; i--) {
            if (hour >= ANNOUNCE_HOURS[i]) {
                chosen = ANNOUNCE_HOURS[i];
                found = true;
                break;
            }
        }
        if (!found) {
            // 자정~02:00 사이: 전날 23시 발표분 사용
            result = safe.minusDays(1);
            chosen = 23;
        }
        return result.withHour(chosen).withMinute(0).withSecond(0).withNano(0);
    }
}
