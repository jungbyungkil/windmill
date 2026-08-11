package com.windmill.service.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.client.CrowdRateClient;
import com.windmill.client.WeatherClient;
import com.windmill.dto.RegionCode;
import com.windmill.util.CrowdCongestionEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 지역별 집중률/기상 원본 데이터를 on-demand로 갱신해 RegionConditionCache에 저장.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerScheduler {

    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private final CrowdRateClient crowdRateClient;
    private final WeatherClient weatherClient;
    private final RegionConditionCache cache;

    public Mono<RegionCondition> ensureFresh(RegionCode region) {
        RegionCondition cached = cache.get(region.getSignguFullCode());
        if (cached != null) {
            return Mono.just(cached);
        }
        return refresh(region);
    }

    private Mono<RegionCondition> refresh(RegionCode region) {
        return Mono.zip(refreshCrowdSnapshot(region), refreshWeatherSnapshot(region).defaultIfEmpty(EMPTY_WEATHER))
                .map(tuple -> {
                    WeatherSnapshot weather = tuple.getT2() == EMPTY_WEATHER ? WeatherSnapshot.EMPTY : tuple.getT2();
                    CrowdSnapshot crowd = tuple.getT1();
                    RegionCondition condition = RegionCondition.builder()
                            .crowdRateByPlaceName(crowd.rates)
                            .crowdCategoryByPlaceName(crowd.categories)
                            .crowdRelativePercentByPlaceName(crowd.relativePercents)
                            .currentPop(weather.pop)
                            .currentPopFcstTime(weather.popFcstTime)
                            .currentTemp(weather.tmp)
                            .dailyMaxTemp(weather.tmx)
                            .refreshedAt(Instant.now())
                            .build();
                    cache.put(region.getSignguFullCode(), condition);
                    log.info("[TriggerScheduler] {} 캐시 갱신 (집중률 {}건, POP={} @ {}시, TMP={}℃, TMX={}℃)",
                            region.getSignguName(), crowd.rates.size(),
                            weather.pop, weather.popFcstTime, weather.tmp, weather.tmx);
                    return condition;
                });
    }

    private Mono<CrowdSnapshot> refreshCrowdSnapshot(RegionCode region) {
        if (!crowdRateClient.isConfigured()) {
            return Mono.just(CrowdSnapshot.EMPTY);
        }
        return crowdRateClient.crowdRateList(region.getLDongRegnCd(), region.getSignguFullCode(), null, 100, 1)
                .map(this::buildCrowdSnapshot)
                .onErrorReturn(CrowdSnapshot.EMPTY);
    }

    /**
     * 장소별로 시리즈를 모아 오늘값·카테고리·평시 평균 대비 %를 만든다.
     * TourAPI cnctrRate는 원래 피크=100 상대값이지만, 수치만 있을 때는
     * 해당 장소 30일 시리즈 평균 대비 %로도 판정할 수 있게 저장한다.
     */
    CrowdSnapshot buildCrowdSnapshot(List<JsonNode> items) {
        Map<String, List<Double>> series = new HashMap<>();
        Map<String, String> categories = new HashMap<>();
        Map<String, Double> todayRates = new HashMap<>();
        String today = LocalDate.now().format(YMD);

        for (JsonNode item : items) {
            String name = item.path("tAtsNm").asText(null);
            if (name == null || name.isBlank()) {
                continue;
            }
            String cat = CrowdCongestionEvaluator.extractCategory(item);
            if (cat != null) {
                categories.putIfAbsent(name, cat);
            }
            Double rate = CrowdCongestionEvaluator.extractNumericRate(item);
            if (rate != null) {
                series.computeIfAbsent(name, k -> new ArrayList<>()).add(rate);
                String baseYmd = item.path("baseYmd").asText("");
                if (baseYmd.equals(today)) {
                    todayRates.put(name, rate);
                } else {
                    todayRates.putIfAbsent(name, rate);
                }
            }
        }

        Map<String, Double> relative = new HashMap<>();
        for (Map.Entry<String, List<Double>> e : series.entrySet()) {
            Double baseline = CrowdCongestionEvaluator.baselineAverage(e.getValue());
            Double todayRate = todayRates.get(e.getKey());
            Double pct = CrowdCongestionEvaluator.relativePercent(todayRate, baseline);
            if (pct != null) {
                relative.put(e.getKey(), pct);
            }
        }
        return new CrowdSnapshot(todayRates, categories, relative);
    }

    private static final WeatherSnapshot EMPTY_WEATHER = WeatherSnapshot.EMPTY;

    private Mono<WeatherSnapshot> refreshWeatherSnapshot(RegionCode region) {
        if (!weatherClient.isConfigured() || region.getWeatherNx() == null || region.getWeatherNy() == null) {
            return Mono.empty();
        }
        return weatherClient.getVillageForecast(region.getWeatherNx(), region.getWeatherNy())
                .map(this::extractWeatherSnapshot)
                .filter(snapshot -> snapshot.pop != null || snapshot.tmp != null || snapshot.tmx != null)
                .onErrorResume(e -> Mono.empty());
    }

    private WeatherSnapshot extractWeatherSnapshot(List<JsonNode> items) {
        JsonNode popItem = earliestByCategory(items, "POP");
        JsonNode tmpItem = earliestByCategory(items, "TMP");
        // TMX는 보통 하루 1회(오전 발표) — 오늘 날짜 중 최고값 사용
        Double tmx = maxByCategoryToday(items, "TMX");
        Double pop = popItem == null ? null : popItem.path("fcstValue").asDouble();
        String popTime = popItem == null ? null : popItem.path("fcstTime").asText(null);
        Double tmp = tmpItem == null ? null : tmpItem.path("fcstValue").asDouble();
        return new WeatherSnapshot(pop, popTime, tmp, tmx);
    }

    private Double maxByCategoryToday(List<JsonNode> items, String category) {
        String today = LocalDate.now().format(YMD);
        Double max = null;
        for (JsonNode i : items) {
            if (!category.equals(i.path("category").asText())) {
                continue;
            }
            String date = i.path("fcstDate").asText("");
            if (!date.isEmpty() && !date.equals(today)) {
                continue;
            }
            double v = i.path("fcstValue").asDouble(Double.NaN);
            if (!Double.isFinite(v)) {
                continue;
            }
            if (max == null || v > max) {
                max = v;
            }
        }
        // 오늘 TMX가 없으면 전체 중 가장 이른 TMX
        if (max == null) {
            JsonNode earliest = earliestByCategory(items, category);
            if (earliest != null) {
                max = earliest.path("fcstValue").asDouble();
            }
        }
        return max;
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

    private record WeatherSnapshot(Double pop, String popFcstTime, Double tmp, Double tmx) {
        static final WeatherSnapshot EMPTY = new WeatherSnapshot(null, null, null, null);
    }

    record CrowdSnapshot(Map<String, Double> rates,
                         Map<String, String> categories,
                         Map<String, Double> relativePercents) {
        static final CrowdSnapshot EMPTY = new CrowdSnapshot(Map.of(), Map.of(), Map.of());
    }
}
