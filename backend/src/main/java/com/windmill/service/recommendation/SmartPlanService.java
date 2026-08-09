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
    /** 방문 시작 시각 상한 — 이 시각 이후로는 새 장소 배정하지 않음 */
    private static final LocalTime LATEST_START = LocalTime.of(20, 0);
    /** 일정 윈도우 끝(체류 포함) */
    private static final LocalTime DAY_END = LocalTime.of(21, 0);
    /** 오늘일 때 출발 버퍼(분) — 지금 6시면 6:30부터 */
    private static final int TODAY_LEAD_MINUTES = 30;
    private static final int BASE_STAY_MINUTES = 75;
    private static final int DEFAULT_TRAVEL_MINUTES = 20;
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
            LocalTime dayStart = resolveDayStart(date);
            int capacity = maxStopsForWindow(dayStart);
            int dayLimit = Math.min(perDay, capacity);

            if (dayLimit <= 0) {
                days.add(SmartPlanResponse.DayPlan.builder()
                        .dayIndex(d + 1)
                        .visitDate(date.toString())
                        .label((d + 1) + "일차 · " + formatMd(date))
                        .estimatedDistanceKm(0.0)
                        .stops(List.of())
                        .build());
                continue;
            }

            List<RecommendationCandidate> dayPool = takeNext(remaining, Math.min(dayLimit + 2, Math.max(dayLimit, remaining.size())));
            if (dayPool.isEmpty()) {
                days.add(SmartPlanResponse.DayPlan.builder()
                        .dayIndex(d + 1)
                        .visitDate(date.toString())
                        .label((d + 1) + "일차 · " + formatMd(date))
                        .estimatedDistanceKm(0.0)
                        .stops(List.of())
                        .build());
                continue;
            }
            List<RecommendationCandidate> routed = RouteOptimizer.optimize(dayPool);
            if (routed.size() > dayLimit) {
                List<RecommendationCandidate> leftover = new ArrayList<>(routed.subList(dayLimit, routed.size()));
                routed = new ArrayList<>(routed.subList(0, dayLimit));
                remaining.addAll(0, leftover);
            }
            assignTimes(routed, dayStart);
            // 시작 시각이 너무 늦은 항목은 제거
            routed = routed.stream()
                    .filter(s -> {
                        LocalTime t = parseTime(s.getSuggestedTime());
                        return t != null && !t.isAfter(LATEST_START);
                    })
                    .collect(Collectors.toCollection(ArrayList::new));

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

        boolean sameDayPlan = targetDays.size() == 1 && targetDays.get(0).equals(LocalDate.now());
        String summary = buildSummary(rain, heat, crowdFiltered, totalKm, allStops.size(), targetDays.size(),
                sameDayPlan ? resolveDayStart(targetDays.get(0)) : null);
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

    /**
     * 방문일 기준 첫 일정 시각.
     * 오늘이면 지금+30분을 30분 단위로 올린 시각(예: 18:05 → 18:30).
     * 미래 날짜는 09:00.
     */
    LocalTime resolveDayStart(LocalDate visitDate) {
        if (visitDate != null && visitDate.equals(LocalDate.now())) {
            LocalTime soon = LocalTime.now().plusMinutes(TODAY_LEAD_MINUTES).withSecond(0).withNano(0);
            int minute = soon.getMinute();
            LocalTime rounded;
            if (minute == 0) {
                rounded = soon;
            } else if (minute <= 30) {
                rounded = soon.withMinute(30);
            } else {
                rounded = soon.plusHours(1).withMinute(0);
            }
            if (rounded.isBefore(DAY_START)) {
                return DAY_START;
            }
            return rounded;
        }
        return DAY_START;
    }

    /** 시작~하루 끝 사이에 체류·이동을 넣었을 때 담을 수 있는 최대 장소 수 */
    int maxStopsForWindow(LocalTime start) {
        if (start == null || !start.isBefore(DAY_END) || start.isAfter(LATEST_START)) {
            return 0;
        }
        long minutes = ChronoUnit.MINUTES.between(start, DAY_END);
        int slot = BASE_STAY_MINUTES + DEFAULT_TRAVEL_MINUTES;
        int max = (int) (minutes / slot);
        if (max <= 0 && ChronoUnit.MINUTES.between(start, LATEST_START) >= 0) {
            // 최소 1곳(짧은 방문)은 허용 — 시작이 LATEST_START 이하면
            return start.isAfter(LATEST_START) ? 0 : 1;
        }
        return Math.min(Math.max(max, 1), DEFAULT_PLACE_COUNT);
    }

    private void assignTimes(List<RecommendationCandidate> stops, LocalTime dayStart) {
        LocalTime cursor = dayStart != null ? dayStart : DAY_START;
        for (int i = 0; i < stops.size(); i++) {
            RecommendationCandidate stop = stops.get(i);
            if (cursor.isAfter(LATEST_START)) {
                stop.setSuggestedTime(LATEST_START.format(TIME_FORMAT));
            } else {
                stop.setSuggestedTime(cursor.format(TIME_FORMAT));
            }
            int travel = DEFAULT_TRAVEL_MINUTES;
            if (i + 1 < stops.size() && stops.get(i + 1).getDistanceKm() != null) {
                travel = (int) Math.ceil(stops.get(i + 1).getDistanceKm() * MINUTES_PER_KM);
                travel = Math.max(10, Math.min(travel, 90));
            } else if (i + 1 >= stops.size()) {
                travel = 0;
            }
            cursor = cursor.plusMinutes(BASE_STAY_MINUTES + travel);
        }
    }

    private static LocalTime parseTime(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(hhmm.trim(), TIME_FORMAT);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildSummary(boolean rain, boolean heat, boolean crowdFiltered, double totalKm,
                                int count, int dayCount, LocalTime todayStart) {
        List<String> parts = new ArrayList<>();
        if (dayCount > 1) {
            parts.add(dayCount + "일 일정");
        }
        if (todayStart != null) {
            parts.add("오늘 " + todayStart.format(TIME_FORMAT) + "부터 · 남은 시간에 맞춰 " + count + "곳");
        } else {
            parts.add("혼잡도 낮은 장소 " + count + "곳");
        }
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
