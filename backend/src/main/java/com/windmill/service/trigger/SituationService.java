package com.windmill.service.trigger;

import com.windmill.dto.RegionCode;
import com.windmill.dto.SituationSummaryResponse;
import com.windmill.service.region.RegionCodeService;
import com.windmill.util.CrowdCongestionEvaluator;
import com.windmill.util.TriggerThresholds;
import com.windmill.util.WeatherGridUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 현재 위치(또는 선택 지역) 기반 실시간 상황 요약.
 * 기상청 격자 매칭 + 집중률 캐시로 앱 진입 시 한 줄 요약을 만든다.
 */
@Service
@RequiredArgsConstructor
public class SituationService {

    private final RegionCodeService regionCodeService;
    private final TriggerScheduler triggerScheduler;

    public Mono<SituationSummaryResponse> summarizeByLocation(double lat, double lon) {
        WeatherGridUtils.Grid grid = WeatherGridUtils.toGrid(lat, lon);
        RegionCode region = nearestRegion(grid.nx(), grid.ny());
        if (region == null) {
            return Mono.just(SituationSummaryResponse.builder()
                    .headline("근처 지역을 찾지 못했어요")
                    .detail("위치를 허용하거나 여행 지역을 직접 선택해주세요.")
                    .tips(List.of())
                    .build());
        }
        return summarizeRegion(region);
    }

    public Mono<SituationSummaryResponse> summarizeByRegion(String signguFullCode) {
        RegionCode region = regionCodeService.find(signguFullCode).orElse(null);
        if (region == null) {
            return Mono.error(new IllegalArgumentException("존재하지 않는 지역코드: " + signguFullCode));
        }
        return summarizeRegion(region);
    }

    private Mono<SituationSummaryResponse> summarizeRegion(RegionCode region) {
        return triggerScheduler.ensureFresh(region)
                .map(condition -> {
                    Double temp = condition.heatProxyTemp();
                    Double pop = condition.getCurrentPop();
                    boolean heatWarning = temp != null && temp >= TriggerThresholds.HEAT_WARNING_TMX;
                    boolean heat = temp != null && temp >= TriggerThresholds.HEAT_ADVISORY_TMX;
                    boolean rain = pop != null && pop >= TriggerThresholds.WEATHER_POP_THRESHOLD;
                    int crowded = countCrowdedPlaces(condition);

                    List<String> tips = new ArrayList<>();
                    if (heatWarning) {
                        tips.add("최고기온 35℃ 이상(폭염경보 수준)이에요. 실내 코스·수분 섭취를 권해요.");
                    } else if (heat) {
                        tips.add("최고기온 33℃ 이상(폭염주의보 수준)이에요. 실내 코스·수분 섭취를 권해요.");
                    }
                    if (rain) {
                        tips.add("비 소식이 있어요. 실내 일정을 미리 준비하세요.");
                    }
                    if (crowded > 0) {
                        tips.add("혼잡 예상 장소가 " + crowded + "곳 있어요. 여유로운 곳으로 바꿔보세요.");
                    }
                    if (tips.isEmpty()) {
                        tips.add("지금은 순항 중이에요. 바람따라 스마트 일정으로 떠나볼까요?");
                    }

                    String weatherLabel = rain ? "비/소나기 주의"
                            : heatWarning ? "폭염경보 수준"
                            : heat ? "폭염주의보 수준"
                            : "대체로 무난";
                    String headline;
                    if (heat || rain) {
                        headline = region.getSidoName() + " " + region.getSignguName() + " · 상황 주의";
                    } else {
                        headline = region.getSidoName() + " " + region.getSignguName() + " · 여행하기 좋아요";
                    }

                    String detail = String.format("최고기온 %s · 강수확률 %s · 혼잡 예상 %d곳",
                            temp == null ? "-" : Math.round(temp) + "℃",
                            pop == null ? "-" : Math.round(pop) + "%",
                            crowded);

                    return SituationSummaryResponse.builder()
                            .regionCode(region.getSignguFullCode())
                            .regionDisplayName(region.getSidoName() + " " + region.getSignguName())
                            .temperature(temp)
                            .precipitationProbability(pop)
                            .weatherLabel(weatherLabel)
                            .heatAlert(heat)
                            .rainAlert(rain)
                            .crowdedPlaceCount(crowded)
                            .headline(headline)
                            .detail(detail)
                            .tips(tips)
                            .build();
                });
    }

    private static int countCrowdedPlaces(RegionCondition condition) {
        if (condition.getCrowdRateByPlaceName() == null || condition.getCrowdRateByPlaceName().isEmpty()) {
            return 0;
        }
        int n = 0;
        for (String name : condition.getCrowdRateByPlaceName().keySet()) {
            if (CrowdCongestionEvaluator.evaluate(
                    condition.getCrowdCategory(name),
                    condition.getCrowdRelativePercent(name),
                    condition.getCrowdRate(name)).isTriggered()) {
                n++;
            }
        }
        return n;
    }

    private RegionCode nearestRegion(int nx, int ny) {
        return regionCodeService.all().stream()
                .filter(r -> r.getWeatherNx() != null && r.getWeatherNy() != null)
                .min(Comparator.comparingInt(r -> {
                    int dx = Integer.parseInt(r.getWeatherNx()) - nx;
                    int dy = Integer.parseInt(r.getWeatherNy()) - ny;
                    return dx * dx + dy * dy;
                }))
                .orElse(null);
    }
}
