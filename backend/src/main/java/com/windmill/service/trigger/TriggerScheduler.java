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
        return Mono.zip(refreshCrowdRates(region), refreshWeatherSnapshot(region).defaultIfEmpty(EMPTY_WEATHER))
                .map(tuple -> {
                    WeatherSnapshot weather = tuple.getT2() == EMPTY_WEATHER ? WeatherSnapshot.EMPTY : tuple.getT2();
                    RegionCondition condition = RegionCondition.builder()
                            .crowdRateByPlaceName(tuple.getT1())
                            .currentPop(weather.pop)
                            .currentPopFcstTime(weather.popFcstTime)
                            .currentTemp(weather.temp)
                            .refreshedAt(Instant.now())
                            .build();
                    cache.put(region.getSignguFullCode(), condition);
                    log.info("[TriggerScheduler] {} 캐시 갱신 완료 (집중률 {}건, POP={} @ {}시, TMP={}℃)",
                            region.getSignguName(), condition.getCrowdRateByPlaceName().size(),
                            weather.pop, weather.popFcstTime, weather.temp);
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

    private static final WeatherSnapshot EMPTY_WEATHER = WeatherSnapshot.EMPTY;

    private Mono<WeatherSnapshot> refreshWeatherSnapshot(RegionCode region) {
        if (!weatherClient.isConfigured() || region.getWeatherNx() == null || region.getWeatherNy() == null) {
            return Mono.empty();
        }
        return weatherClient.getVillageForecast(region.getWeatherNx(), region.getWeatherNy())
                .map(this::extractWeatherSnapshot)
                .filter(snapshot -> snapshot.pop != null || snapshot.temp != null)
                .onErrorResume(e -> Mono.empty());
    }

    private WeatherSnapshot extractWeatherSnapshot(List<JsonNode> items) {
        JsonNode popItem = earliestByCategory(items, "POP");
        JsonNode tmpItem = earliestByCategory(items, "TMP");
        Double pop = popItem == null ? null : popItem.path("fcstValue").asDouble();
        String popTime = popItem == null ? null : popItem.path("fcstTime").asText(null);
        Double temp = tmpItem == null ? null : tmpItem.path("fcstValue").asDouble();
        // 일최고기온(TMX)이 있으면 폭염 판정에 더 적합하므로 더 높은 값 사용
        JsonNode tmxItem = earliestByCategory(items, "TMX");
        if (tmxItem != null) {
            double tmx = tmxItem.path("fcstValue").asDouble();
            if (temp == null || tmx > temp) {
                temp = tmx;
            }
        }
        return new WeatherSnapshot(pop, popTime, temp);
    }

    private JsonNode earliestByCategory(List<JsonNode> items, String category) {
        return items.stream()
                .filter(i -> category.equals(i.path("category").asText()))
                .min((a, b) -> {
                    String ta = a.path("fcstDate").asText("") + a.path("fcstTime").asText("");
                    String tb = b.path("fcstDate").asText("") + b.path("fcstTime").asText("");
                    return ta.compareTo(tb);
                })
                .orElse(null);
    }

    private record WeatherSnapshot(Double pop, String popFcstTime, Double temp) {
        static final WeatherSnapshot EMPTY = new WeatherSnapshot(null, null, null);
    }
}
