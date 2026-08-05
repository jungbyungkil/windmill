package com.windmill.service.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.windmill.client.CrowdRateClient;
import com.windmill.client.WeatherClient;
import com.windmill.dto.RegionCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 지역별 집중률/기상 원본 데이터를 on-demand로 갱신해 RegionConditionCache에 저장.
 * 예전엔 속초 한 지역만 30분 고정 스케줄로 프리페치했지만, 전국 250여 지역으로 확장되며
 * 매번 전체를 프리페치하면 API 쿼터 낭비가 커 실제 활성 일정이 있는 지역만 필요 시점에 갱신한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerScheduler {

    private final CrowdRateClient crowdRateClient;
    private final WeatherClient weatherClient;
    private final RegionConditionCache cache;

    /** 캐시가 없거나 TTL(30분)이 지났으면 그 지역만 갱신 후 반환. 최신이면 외부 API 호출 없이 즉시 반환 */
    public Mono<RegionCondition> ensureFresh(RegionCode region) {
        RegionCondition cached = cache.get(region.getSignguFullCode());
        if (cached != null) {
            return Mono.just(cached);
        }
        return refresh(region);
    }

    private Mono<RegionCondition> refresh(RegionCode region) {
        return Mono.zip(refreshCrowdRates(region), refreshWeather(region).defaultIfEmpty(EMPTY_POP))
                .map(tuple -> {
                    JsonNode popItem = tuple.getT2();
                    Double pop = popItem == EMPTY_POP ? null : popItem.path("fcstValue").asDouble();
                    String fcstTime = popItem == EMPTY_POP ? null : popItem.path("fcstTime").asText(null);
                    RegionCondition condition = RegionCondition.builder()
                            .crowdRateByPlaceName(tuple.getT1())
                            .currentPop(pop)
                            .currentPopFcstTime(fcstTime)
                            .refreshedAt(Instant.now())
                            .build();
                    cache.put(region.getSignguFullCode(), condition);
                    log.info("[TriggerScheduler] {} 캐시 갱신 완료 (집중률 {}건, POP={} @ {}시)",
                            region.getSignguName(), condition.getCrowdRateByPlaceName().size(), pop, fcstTime);
                    return condition;
                });
    }

    private Mono<Map<String, Double>> refreshCrowdRates(RegionCode region) {
        if (!crowdRateClient.isConfigured()) {
            return Mono.just(Map.of());
        }
        // legacy areaCd/signguCd는 LDONG에서 파생됨: areaCd=lDongRegnCd, signguCd=signguFullCode
        return crowdRateClient.crowdRateList(region.getLDongRegnCd(), region.getSignguFullCode(), null, 100, 1)
                .map(items -> {
                    Map<String, Double> today = new HashMap<>();
                    for (JsonNode item : items) {
                        String name = item.path("tAtsNm").asText(null);
                        if (name != null && !today.containsKey(name)) {
                            today.put(name, item.path("cnctrRate").asDouble());
                        }
                    }
                    return today;
                })
                .onErrorReturn(Map.of());
    }

    /** Mono는 null을 emit할 수 없어, 날씨 미조회/미설정 상태를 나타내는 빈 노드 sentinel */
    private static final JsonNode EMPTY_POP = NullNode.getInstance();

    private Mono<JsonNode> refreshWeather(RegionCode region) {
        if (!weatherClient.isConfigured() || region.getWeatherNx() == null || region.getWeatherNy() == null) {
            return Mono.empty();
        }
        return weatherClient.getVillageForecast(region.getWeatherNx(), region.getWeatherNy())
                .flatMap(items -> {
                    JsonNode popItem = extractLatestPopItem(items);
                    return popItem == null ? Mono.empty() : Mono.just(popItem);
                })
                .onErrorResume(e -> Mono.empty());
    }

    /** 발표 회차 예보 중 가장 이른(현재에 가장 가까운) 시각의 강수확률(POP) 항목 원본을 사용 */
    private JsonNode extractLatestPopItem(List<JsonNode> items) {
        return items.stream()
                .filter(i -> "POP".equals(i.path("category").asText()))
                .min((a, b) -> {
                    String ta = a.path("fcstDate").asText("") + a.path("fcstTime").asText("");
                    String tb = b.path("fcstDate").asText("") + b.path("fcstTime").asText("");
                    return ta.compareTo(tb);
                })
                .orElse(null);
    }
}
