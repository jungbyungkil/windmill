package com.windmill.service.recommendation;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RecommendationRequest;
import com.windmill.dto.RegionCode;
import com.windmill.dto.SmartPlanResponse;
import com.windmill.service.region.RegionCodeService;
import com.windmill.service.trigger.RegionCondition;
import com.windmill.service.trigger.TriggerScheduler;
import com.windmill.service.trip.TripRecordService;
import com.windmill.util.RouteOptimizer;
import com.windmill.util.TriggerThresholds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 바람따라 핵심 플로우: TourAPI 기반 후보를
 * 1) 혼잡도(집중률) 낮은 순으로 고르고
 * 2) 날씨(비/폭염)면 실내 코스로 전환하며
 * 3) 좌표 기준 최근접 동선으로 꼬임을 줄인 뒤
 * 4) 이동 거리를 반영해 방문 시각을 배정한다.
 * 다일 여행이면 일차별로 나눠 일정을 짠다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmartPlanService {

    private static final int DEFAULT_PLACE_COUNT = 5;
    private static final int PER_DAY_PLACES = 4;
    private static final int MAX_DAYS = 7;
    private static final LocalTime DAY_START = LocalTime.of(9, 0);
    private static final int BASE_STAY_MINUTES = 75;
    private static final int MINUTES_PER_KM = 12;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final RecommendationPipeline recommendationPipeline;
    private final TriggerScheduler triggerScheduler;
    private final RegionCodeService regionCodeService;
    private final TripRecordService tripRecordService;

    public Mono<SmartPlanResponse> build(Itinerary itinerary, int placeCount) {
        return build(itinerary, placeCount, null);
    }

    /**
     * @param forDate null이면 여행 기간 전체(최대 7일), 지정 시 해당 일자만
     */
    public Mono<SmartPlanResponse> build(Itinerary itinerary, int placeCount, LocalDate forDate) {
        int perDay = placeCount <= 0 ? (forDate != null ? DEFAULT_PLACE_COUNT : PER_DAY_PLACES)
                : Math.min(placeCount, 8);
        RegionCode region = regionCodeService.find(itinerary.getSignguFullCode())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지역코드: " + itinerary.getSignguFullCode()));

        List<LocalDate> targetDays = resolveDays(itinerary, forDate);
        int need = Math.max(perDay * targetDays.size(), perDay);

        return triggerScheduler.ensureFresh(region)
                .onErrorReturn(RegionCondition.builder().crowdRateByPlaceName(java.util.Map.of()).build())
                .flatMap(condition -> {
                    boolean rain = condition.getCurrentPop() != null
                            && condition.getCurrentPop() >= TriggerThresholds.WEATHER_POP_THRESHOLD;
                    boolean heat = condition.getCurrentTemp() != null
                            && condition.getCurrentTemp() >= TriggerThresholds.HEAT_TEMP_THRESHOLD;

                    RecommendationRequest.AvoidanceHint avoid = null;
                    if (heat) {
                        avoid = RecommendationRequest.AvoidanceHint.HEAT;
                    } else if (rain) {
                        avoid = RecommendationRequest.AvoidanceHint.WEATHER;
                    }

                    RecommendationRequest request = buildRequest(itinerary, avoid);
                    final boolean rainFinal = rain;
                    final boolean heatFinal = heat;

                    return recommendationPipeline.recommend(request)
                            .map(candidates -> assemble(candidates, perDay, need, targetDays, rainFinal, heatFinal));
                });
    }

    private List<LocalDate> resolveDays(Itinerary itinerary, LocalDate forDate) {
        if (forDate != null) {
            return List.of(forDate);
        }
        LocalDate start = itinerary.getStartDate() != null ? itinerary.getStartDate() : LocalDate.now();
        LocalDate end = itinerary.getEndDate() != null ? itinerary.getEndDate() : start;
        if (end.isBefore(start)) {
            end = start;
        }
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        int days = (int) Math.min(Math.max(span, 1), MAX_DAYS);
        List<LocalDate> list = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            list.add(start.plusDays(i));
        }
        return list;
    }

    private RecommendationRequest buildRequest(Itinerary itinerary, RecommendationRequest.AvoidanceHint avoid) {
        List<String> excludeContentIds = itinerary.getItems().stream()
                .map(ItineraryItem::getContentId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toList());
        List<String> excludePlaceNames = List.copyOf(tripRecordService.getBadPlaceNames(itinerary.getSessionUuid()));
        ItineraryItem origin = itinerary.getItems().isEmpty()
                ? null
                : itinerary.getItems().get(itinerary.getItems().size() - 1);

        return RecommendationRequest.builder()
                .regionCode(itinerary.getSignguFullCode())
                .withPet(itinerary.isWithPet())
                .companionType(itinerary.getCompanionType())
                .tags(List.of("#실내", "#자연", "#맛집"))
                .excludeContentIds(excludeContentIds)
                .excludePlaceNames(excludePlaceNames)
                .avoidanceHint(avoid)
                .originContentId(origin == null ? null : origin.getContentId())
                .originContentTypeId(origin == null ? null : origin.getContentTypeId())
                .build();
    }

    private SmartPlanResponse assemble(List<RecommendationCandidate> raw, int perDay, int need,
                                       List<LocalDate> targetDays, boolean rain, boolean heat) {
        List<RecommendationCandidate> comfortable = raw.stream()
                .filter(c -> c.getCrowdRate() == null || c.getCrowdRate() < TriggerThresholds.CROWD_RATE_THRESHOLD)
                .collect(Collectors.toCollection(ArrayList::new));
        boolean crowdFiltered = comfortable.size() >= Math.min(3, need) && comfortable.size() < raw.size();
        List<RecommendationCandidate> pool = crowdFiltered ? comfortable : new ArrayList<>(raw);

        pool.sort(Comparator
                .comparing((RecommendationCandidate c) -> c.getCrowdRate() == null ? Double.POSITIVE_INFINITY : c.getCrowdRate())
                .thenComparing(c -> c.getThumbnailUrl() == null || c.getThumbnailUrl().isBlank() ? 1 : 0));

        int poolCap = Math.min(pool.size(), Math.max(need + 4, perDay * 3));
        if (pool.size() > poolCap) {
            pool = new ArrayList<>(pool.subList(0, poolCap));
        }

        List<RecommendationCandidate> remaining = new ArrayList<>(pool);
        List<SmartPlanResponse.DayPlan> days = new ArrayList<>();
        List<RecommendationCandidate> allStops = new ArrayList<>();
        double totalKm = 0;
        int globalRank = 1;

        for (int d = 0; d < targetDays.size(); d++) {
            LocalDate date = targetDays.get(d);
            List<RecommendationCandidate> dayPool = takeNext(remaining, Math.min(perDay + 2, Math.max(perDay, remaining.size())));
            if (dayPool.isEmpty()) {
                days.add(SmartPlanResponse.DayPlan.builder()
                        .dayIndex(d + 1)
                        .visitDate(date.toString())
                        .label((d + 1) + "일차")
                        .estimatedDistanceKm(0.0)
                        .stops(List.of())
                        .build());
                continue;
            }
            List<RecommendationCandidate> routed = RouteOptimizer.optimize(dayPool);
            if (routed.size() > perDay) {
                // 잘라낸 나머지는 다음 날 후보로 되돌림
                List<RecommendationCandidate> leftover = new ArrayList<>(routed.subList(perDay, routed.size()));
                routed = new ArrayList<>(routed.subList(0, perDay));
                remaining.addAll(0, leftover);
            }
            assignTimes(routed);
            double dayKm = RouteOptimizer.totalDistanceKm(routed);
            totalKm += dayKm;

            for (RecommendationCandidate stop : routed) {
                stop.setVisitDate(date.toString());
                stop.setRank(globalRank++);
                if (stop.getOneLiner() == null || stop.getOneLiner().isBlank()) {
                    stop.setOneLiner(defaultLine(stop));
                }
            }
            allStops.addAll(routed);
            days.add(SmartPlanResponse.DayPlan.builder()
                    .dayIndex(d + 1)
                    .visitDate(date.toString())
                    .label((d + 1) + "일차 · " + formatMd(date))
                    .estimatedDistanceKm(Math.round(dayKm * 10.0) / 10.0)
                    .stops(routed)
                    .build());
        }

        String summary = buildSummary(rain, heat, crowdFiltered, totalKm, allStops.size(), targetDays.size());
        log.info("[SmartPlan] days={}, stops={}, rain={}, heat={}, crowdFiltered={}, totalKm={}",
                targetDays.size(), allStops.size(), rain, heat, crowdFiltered, totalKm);

        return SmartPlanResponse.builder()
                .strategySummary(summary)
                .weatherAdjusted(rain)
                .heatAdjusted(heat)
                .crowdFiltered(crowdFiltered)
                .estimatedTotalDistanceKm(Math.round(totalKm * 10.0) / 10.0)
                .candidateCount(raw.size())
                .visitDate(targetDays.size() == 1 ? targetDays.get(0).toString() : null)
                .dayCount(targetDays.size())
                .days(days)
                .stops(allStops)
                .build();
    }

    private List<RecommendationCandidate> takeNext(List<RecommendationCandidate> remaining, int n) {
        List<RecommendationCandidate> taken = new ArrayList<>();
        while (!remaining.isEmpty() && taken.size() < n) {
            taken.add(remaining.remove(0));
        }
        return taken;
    }

    private void assignTimes(List<RecommendationCandidate> stops) {
        LocalTime cursor = DAY_START;
        for (int i = 0; i < stops.size(); i++) {
            RecommendationCandidate stop = stops.get(i);
            stop.setSuggestedTime(cursor.format(TIME_FORMAT));
            int travel = 0;
            if (i + 1 < stops.size() && stops.get(i + 1).getDistanceKm() != null) {
                travel = (int) Math.ceil(stops.get(i + 1).getDistanceKm() * MINUTES_PER_KM);
            } else if (i + 1 < stops.size()) {
                travel = 20;
            }
            cursor = cursor.plusMinutes(BASE_STAY_MINUTES + travel);
            if (cursor.isAfter(LocalTime.of(20, 0))) {
                cursor = LocalTime.of(20, 0);
            }
        }
    }

    private String buildSummary(boolean rain, boolean heat, boolean crowdFiltered, double totalKm,
                                int count, int dayCount) {
        List<String> parts = new ArrayList<>();
        if (dayCount > 1) {
            parts.add(dayCount + "일 일정");
        }
        parts.add("혼잡도 낮은 장소 " + count + "곳");
        parts.add("동선 최소화" + (totalKm > 0 ? String.format(" (약 %.1fkm)", totalKm) : ""));
        if (crowdFiltered) {
            parts.add("붐비는 곳 제외");
        }
        if (heat) {
            parts.add("폭염 → 실내 코스");
        } else if (rain) {
            parts.add("비 예보 → 실내 코스");
        }
        return String.join(" · ", parts);
    }

    private String formatMd(LocalDate date) {
        return date.getMonthValue() + "/" + date.getDayOfMonth();
    }

    private String defaultLine(RecommendationCandidate c) {
        if (c.getCrowdRate() != null) {
            return String.format("여유율 %.0f%% · 동선에 자연스럽게 이어져요", 100 - c.getCrowdRate());
        }
        return "TourAPI 검증 장소 · 동선 최적화에 포함됐어요";
    }
}
