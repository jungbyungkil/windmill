package com.windmill.service.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.client.CrowdRateClient;
import com.windmill.client.WeatherClient;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 30분마다 속초 지역의 집중률/기상 원본 데이터를 갱신해 RegionConditionCache에 저장.
 * 프론트 폴링(trigger-status)은 이 캐시만 조회하므로 매 요청마다 외부 API를 호출하지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerScheduler {

    private static final long THIRTY_MINUTES_MS = 30 * 60 * 1000L;

    private final CrowdRateClient crowdRateClient;
    private final WeatherClient weatherClient;
    private final RegionConditionCache cache;

    @Value("${windmill.region.sokcho.legacy-area-cd}")
    private String areaCd;

    @Value("${windmill.region.sokcho.legacy-signgu-cd}")
    private String signguCd;

    @Value("${windmill.region.sokcho.weather-nx}")
    private String nx;

    @Value("${windmill.region.sokcho.weather-ny}")
    private String ny;

    @PostConstruct
    public void initialRefresh() {
        refresh();
    }

    @Scheduled(fixedRate = THIRTY_MINUTES_MS)
    public void refresh() {
        refreshCrowdRates();
        refreshWeather();
    }

    private void refreshCrowdRates() {
        if (!crowdRateClient.isConfigured()) {
            return;
        }
        crowdRateClient.crowdRateList(areaCd, signguCd, null, 100, 1)
                .subscribe(items -> {
                    Map<String, Double> today = new HashMap<>();
                    for (JsonNode item : items) {
                        String name = item.path("tAtsNm").asText(null);
                        if (name != null && !today.containsKey(name)) {
                            today.put(name, item.path("cnctrRate").asDouble());
                        }
                    }
                    cache.updateCrowdRates(today);
                    log.info("[TriggerScheduler] 집중률 캐시 갱신 완료 ({}건)", today.size());
                }, e -> log.error("[TriggerScheduler] 집중률 갱신 실패: {}", e.getMessage()));
    }

    private void refreshWeather() {
        if (!weatherClient.isConfigured()) {
            return;
        }
        weatherClient.getVillageForecast(nx, ny)
                .subscribe(items -> {
                    Double pop = extractLatestPop(items);
                    cache.updatePop(pop);
                    log.info("[TriggerScheduler] 기상 캐시 갱신 완료 (POP={})", pop);
                }, e -> log.error("[TriggerScheduler] 기상 갱신 실패: {}", e.getMessage()));
    }

    /** 발표 회차 예보 중 가장 이른(현재에 가장 가까운) 시각의 강수확률(POP)을 사용 */
    private Double extractLatestPop(List<JsonNode> items) {
        return items.stream()
                .filter(i -> "POP".equals(i.path("category").asText()))
                .min((a, b) -> {
                    String ta = a.path("fcstDate").asText("") + a.path("fcstTime").asText("");
                    String tb = b.path("fcstDate").asText("") + b.path("fcstTime").asText("");
                    return ta.compareTo(tb);
                })
                .map(i -> i.path("fcstValue").asDouble())
                .orElse(null);
    }
}
