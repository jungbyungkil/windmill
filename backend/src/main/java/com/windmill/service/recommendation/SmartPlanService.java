package com.windmill.service.recommendation;

import com.windmill.domain.CompanionType;
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
import com.windmill.util.ClosingTimeGate;
import com.windmill.util.CrowdCongestionEvaluator;
import com.windmill.util.GeoUtils;
import com.windmill.util.PlaceTagSanitizer;
import com.windmill.util.KoreaClock;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 바람따라 핵심 플로우: TourAPI 기반 후보를
 * 1) 인기(집중률) 높은 명소를 우선하고, 붐비는 곳은 비교적 한산한 오전에 두며
 * 2) 날씨(비/폭염)면 실내 코스로 전환하고
 * 3) 현실적인 당일치기 리듬(오전→점심→오후→저녁)으로 배치한다.
 * 가족 여행은 관광을 줄이고 식사 슬롯을 반드시 넣는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SmartPlanService {

    private static final int MAX_DAYS = 7;
    private static final LocalTime DAY_START = LocalTime.of(9, 0);
    private static final LocalTime LATEST_START = LocalTime.of(20, 0);
    private static final LocalTime DAY_END = LocalTime.of(21, 0);
    private static final LocalTime LUNCH_ANCHOR = LocalTime.of(12, 0);
    private static final LocalTime DINNER_ANCHOR = LocalTime.of(18, 0);
    private static final int TODAY_LEAD_MINUTES = 30;
    private static final int ATTRACTION_STAY = 75;
    private static final int FAMILY_ATTRACTION_STAY = 90;
    private static final int MEAL_STAY = 60;
    private static final int DEFAULT_TRAVEL_MINUTES = 20;
    private static final int MINUTES_PER_KM = 12;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    /** 슬롯 확장(오전2/오후2/저녁후) 판단용 근접 기준 - ProximityRanking.NEAR_KM/AnchorPlanService와 동일값 */
    private static final double SLOT_EXPANSION_NEAR_KM = 1.5;

    private final RecommendationPipeline recommendationPipeline;
    private final TriggerScheduler triggerScheduler;
    private final RegionCodeService regionCodeService;
    private final TripRecordService tripRecordService;

    public Mono<SmartPlanResponse> build(Itinerary itinerary, int placeCount) {
        return build(itinerary, placeCount, null);
    }

    /**
     * "당일치기 시작하기" 전용 표준 7슬롯 일정.
     * 지금 시각과 무관하게 하루 전체를 채운다: 그외 일정 4 + 식당 2 + 카페 1.
     * (요청의 식당2·카페1·그외3에 슬롯 7개 채우기를 맞추면 그외는 4곳이 된다.)
     * 여행 중 "다시 짜기"는 {@link #build}의 시간창 판정을 그대로 쓴다.
     */
    public Mono<SmartPlanResponse> buildStandardDayPlan(Itinerary itinerary) {
        RegionCode region = regionCodeService.find(itinerary.getSignguFullCode())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지역코드: " + itinerary.getSignguFullCode()));
        LocalDate date = itinerary.getStartDate() != null ? itinerary.getStartDate() : KoreaClock.today();
        CompanionType companion = itinerary.getCompanionType();

        return triggerScheduler.ensureFresh(region)
                .onErrorReturn(RegionCondition.builder().crowdRateByPlaceName(java.util.Map.of()).build())
                .flatMap(condition -> {
                    boolean rain = condition.getCurrentPop() != null
                            && condition.getCurrentPop() >= TriggerThresholds.WEATHER_POP_THRESHOLD;
                    boolean heat = condition.heatProxyTemp() != null
                            && condition.heatProxyTemp() >= TriggerThresholds.HEAT_ADVISORY_TMX;
                    RecommendationRequest.AvoidanceHint avoid = heat
                            ? RecommendationRequest.AvoidanceHint.HEAT
                            : rain ? RecommendationRequest.AvoidanceHint.WEATHER : null;

                    List<String> attractionTags = AttractionThemeSelector.select(
                            companion, itinerary.getAdultAgeGroup(), itinerary.getChildAges());
                    RecommendationRequest attractionReq = buildRequest(itinerary, avoid, attractionTags, true);
                    RecommendationRequest foodReq = buildRequest(itinerary, avoid, List.of("#맛집"), true);
                    RecommendationRequest cafeReq = buildRequest(itinerary, avoid, List.of("#카페"), true);

                    return Mono.zip(
                                    recommendationPipeline.recommend(attractionReq).onErrorReturn(List.of()),
                                    recommendationPipeline.recommend(foodReq).onErrorReturn(List.of()),
                                    recommendationPipeline.recommend(cafeReq).onErrorReturn(List.of()))
                            .map(tuple -> assembleStandardDay(
                                    tuple.getT1(), tuple.getT2(), tuple.getT3(),
                                    date, companion, rain, heat));
                });
    }

    private SmartPlanResponse assembleStandardDay(List<RecommendationCandidate> attractionsRaw,
                                                    List<RecommendationCandidate> foodRaw,
                                                    List<RecommendationCandidate> cafeRaw,
                                                    LocalDate date, CompanionType companion,
                                                    boolean rain, boolean heat) {
        List<RecommendationCandidate> attrPool = new ArrayList<>(attractionsRaw);
        List<RecommendationCandidate> foodPool = new ArrayList<>(foodRaw);
        List<RecommendationCandidate> cafePool = new ArrayList<>(cafeRaw);
        splitPools(attrPool, foodPool, cafePool);
        attrPool = new ArrayList<>(sortPopular(attrPool));
        foodPool = new ArrayList<>(sortPopular(foodPool));
        cafePool = new ArrayList<>(sortPopular(cafePool));

        List<RecommendationCandidate> day = assembleStandardSevenSlots(attrPool, foodPool, cafePool);

        fillDistances(day);
        double dayKm = RouteOptimizer.totalDistanceKm(day);
        int rank = 1;
        for (RecommendationCandidate stop : day) {
            stop.setVisitDate(date.toString());
            stop.setRank(rank++);
        }

        boolean crowdFiltered = attractionsRaw.stream()
                .anyMatch(c -> CrowdCongestionEvaluator.fromPeakRelativeRate(c.getCrowdRate()).isTriggered());
        String summary = buildSummary(rain, heat, crowdFiltered, dayKm, day, 1, null, isFamilyPace(companion));

        log.info("[SmartPlan] 표준 7슬롯 일정 생성 - date={}, stops={}, family={}", date, day.size(), isFamilyPace(companion));

        return SmartPlanResponse.builder()
                .strategySummary(summary)
                .weatherAdjusted(rain)
                .heatAdjusted(heat)
                .crowdFiltered(crowdFiltered)
                .estimatedTotalDistanceKm(Math.round(dayKm * 10.0) / 10.0)
                .candidateCount(attractionsRaw.size() + foodRaw.size() + cafeRaw.size())
                .visitDate(date.toString())
                .dayCount(1)
                .days(List.of(SmartPlanResponse.DayPlan.builder()
                        .dayIndex(1)
                        .visitDate(date.toString())
                        .label(formatMd(date))
                        .estimatedDistanceKm(Math.round(dayKm * 10.0) / 10.0)
                        .stops(day)
                        .build()))
                .stops(day)
                .build();
    }

    /**
     * 시각과 무관하게 7칸을 채운다.
     * 오전1 → 오전2 → 점심(식당) → 오후 → 카페 → 저녁 전 → 저녁(식당).
     */
    List<RecommendationCandidate> assembleStandardSevenSlots(List<RecommendationCandidate> attrPool,
                                                               List<RecommendationCandidate> foodPool,
                                                               List<RecommendationCandidate> cafePool) {
        attrPool = new ArrayList<>(sortPopular(attrPool));
        foodPool = new ArrayList<>(sortPopular(foodPool));
        cafePool = new ArrayList<>(sortPopular(cafePool));
        List<RecommendationCandidate> day = new ArrayList<>();
        RecommendationCandidate prev = null;
        Set<String> usedContentIds = new java.util.HashSet<>();

        prev = addForcedSlot(day, attrPool, foodPool, cafePool, prev, usedContentIds,
                LocalTime.of(9, 0), "오전 일정", SlotKind.ATTRACTION);
        prev = addForcedSlot(day, attrPool, foodPool, cafePool, prev, usedContentIds,
                LocalTime.of(10, 30), "오전 일정 2", SlotKind.ATTRACTION);
        prev = addForcedSlot(day, attrPool, foodPool, cafePool, prev, usedContentIds,
                LocalTime.of(12, 0), "점심 식사", SlotKind.MEAL);
        prev = addForcedSlot(day, attrPool, foodPool, cafePool, prev, usedContentIds,
                LocalTime.of(14, 0), "오후 일정", SlotKind.ATTRACTION);
        prev = addForcedSlot(day, attrPool, foodPool, cafePool, prev, usedContentIds,
                LocalTime.of(15, 30), "카페", SlotKind.CAFE);
        prev = addForcedSlot(day, attrPool, foodPool, cafePool, prev, usedContentIds,
                LocalTime.of(17, 0), "저녁 전 일정", SlotKind.ATTRACTION);
        addForcedSlot(day, attrPool, foodPool, cafePool, prev, usedContentIds,
                LocalTime.of(18, 30), "저녁 식사", SlotKind.MEAL);
        return day;
    }

    private RecommendationCandidate addForcedSlot(List<RecommendationCandidate> day,
                                                    List<RecommendationCandidate> attrPool,
                                                    List<RecommendationCandidate> foodPool,
                                                    List<RecommendationCandidate> cafePool,
                                                    RecommendationCandidate prev,
                                                    Set<String> usedContentIds,
                                                    LocalTime time, String label, SlotKind kind) {
        List<RecommendationCandidate> preferred = switch (kind) {
            case MEAL -> foodPool;
            case CAFE -> cafePool;
            case ATTRACTION -> attrPool;
        };
        boolean morningPopular = kind == SlotKind.ATTRACTION && time.isBefore(LocalTime.of(12, 0));
        RecommendationCandidate placed = placeForced(preferred, prev, time, label, kind, morningPopular);
        if (placed == null) {
            List<RecommendationCandidate> fallback = kind == SlotKind.CAFE ? foodPool
                    : kind == SlotKind.MEAL ? cafePool
                    : foodPool;
            placed = placeForced(fallback, prev, time, label, kind, morningPopular);
        }
        if (placed == null && kind != SlotKind.ATTRACTION) {
            placed = placeForced(attrPool, prev, time, label, kind, morningPopular);
        }
        if (placed == null) {
            return prev;
        }
        day.add(placed);
        addUsed(usedContentIds, placed);
        removeUsed(attrPool, usedContentIds);
        removeUsed(foodPool, usedContentIds);
        removeUsed(cafePool, usedContentIds);
        return placed;
    }

    private void splitPools(List<RecommendationCandidate> attrPool,
                            List<RecommendationCandidate> foodPool,
                            List<RecommendationCandidate> cafePool) {
        if (foodPool.isEmpty()) {
            List<RecommendationCandidate> splitFood = new ArrayList<>();
            List<RecommendationCandidate> splitAttr = new ArrayList<>();
            for (RecommendationCandidate c : attrPool) {
                (isFoodCandidate(c) ? splitFood : splitAttr).add(c);
            }
            if (!splitFood.isEmpty()) {
                foodPool.addAll(splitFood);
                attrPool.clear();
                attrPool.addAll(splitAttr);
            }
        }
        if (cafePool.isEmpty()) {
            List<RecommendationCandidate> fromFood = new ArrayList<>();
            foodPool.removeIf(c -> {
                if (isCafeCandidate(c)) {
                    fromFood.add(c);
                    return true;
                }
                return false;
            });
            List<RecommendationCandidate> fromAttr = new ArrayList<>();
            attrPool.removeIf(c -> {
                if (isCafeCandidate(c)) {
                    fromAttr.add(c);
                    return true;
                }
                return false;
            });
            cafePool.addAll(fromFood);
            cafePool.addAll(fromAttr);
        }
        Set<String> foodIds = foodPool.stream()
                .map(RecommendationCandidate::getContentId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Set<String> cafeIds = cafePool.stream()
                .map(RecommendationCandidate::getContentId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        attrPool.removeIf(c -> (c.getContentId() != null && (foodIds.contains(c.getContentId()) || cafeIds.contains(c.getContentId())))
                || isFoodCandidate(c) || isCafeCandidate(c));
        foodPool.removeIf(c -> c.getContentId() != null && cafeIds.contains(c.getContentId()));
    }

    private static void addUsed(Set<String> usedContentIds, RecommendationCandidate candidate) {
        if (candidate.getContentId() != null) {
            usedContentIds.add(candidate.getContentId());
        }
    }

    private static void removeUsed(List<RecommendationCandidate> pool, Set<String> usedContentIds) {
        pool.removeIf(c -> c.getContentId() != null && usedContentIds.contains(c.getContentId()));
    }

    /**
     * pool에서 후보 하나를 골라 targetTime에 강제 배치한다(무조건 채우기 - 마감 게이트에
     * 걸려도 안전 시각으로 당기거나, 그마저 불가능하면 요청 시각 그대로 강행한다). pool이 비어 있으면 null.
     * 오전 관광은 인기(집중률) 높은 곳을, 그 외는 직전 스탑에서 가까운 곳을 고른다.
     */
    private RecommendationCandidate placeForced(List<RecommendationCandidate> pool, RecommendationCandidate origin,
                                                  LocalTime targetTime, String slotLabel, SlotKind kind,
                                                  boolean preferPopular) {
        RecommendationCandidate candidate = preferPopular
                ? takeMostPopularInWindow(pool)
                : takeNearest(pool, origin);
        if (candidate == null) {
            return null;
        }
        placeStop(candidate, targetTime, slotLabel, kind, true);
        attachBackup(candidate, pool);
        return candidate;
    }

    private enum SlotKind { ATTRACTION, MEAL, CAFE }

    /**
     * @param forDate null이면 여행 기간 전체(최대 7일), 지정 시 해당 일자만
     * @param placeCount 하위 호환용(0이면 동행 유형별 자동). 명시 시 관광 상한으로만 참고.
     */
    public Mono<SmartPlanResponse> build(Itinerary itinerary, int placeCount, LocalDate forDate) {
        RegionCode region = regionCodeService.find(itinerary.getSignguFullCode())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지역코드: " + itinerary.getSignguFullCode()));

        List<LocalDate> targetDays = resolveDays(itinerary, forDate);
        CompanionType companion = itinerary.getCompanionType();

        return triggerScheduler.ensureFresh(region)
                .onErrorReturn(RegionCondition.builder().crowdRateByPlaceName(java.util.Map.of()).build())
                .flatMap(condition -> {
                    boolean rain = condition.getCurrentPop() != null
                            && condition.getCurrentPop() >= TriggerThresholds.WEATHER_POP_THRESHOLD;
                    boolean heat = condition.heatProxyTemp() != null
                            && condition.heatProxyTemp() >= TriggerThresholds.HEAT_ADVISORY_TMX;

                    RecommendationRequest.AvoidanceHint avoid = null;
                    if (heat) {
                        avoid = RecommendationRequest.AvoidanceHint.HEAT;
                    } else if (rain) {
                        avoid = RecommendationRequest.AvoidanceHint.WEATHER;
                    }

                    List<String> attractionTags = AttractionThemeSelector.select(
                            companion, itinerary.getAdultAgeGroup(), itinerary.getChildAges());
                    RecommendationRequest attractionReq = buildRequest(itinerary, avoid, attractionTags);
                    RecommendationRequest foodReq = buildRequest(itinerary, avoid, List.of("#맛집"));

                    final boolean rainFinal = rain;
                    final boolean heatFinal = heat;
                    final int placeCap = placeCount > 0 ? Math.min(placeCount, 6) : 0;

                    return Mono.zip(
                                    recommendationPipeline.recommend(attractionReq).onErrorReturn(List.of()),
                                    recommendationPipeline.recommend(foodReq).onErrorReturn(List.of()))
                            .map(tuple -> assemble(
                                    tuple.getT1(), tuple.getT2(), targetDays,
                                    companion, placeCap, rainFinal, heatFinal));
                });
    }

    private List<LocalDate> resolveDays(Itinerary itinerary, LocalDate forDate) {
        if (forDate != null) {
            return List.of(forDate);
        }
        LocalDate start = itinerary.getStartDate() != null ? itinerary.getStartDate() : KoreaClock.today();
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

    private RecommendationRequest buildRequest(Itinerary itinerary,
                                                 RecommendationRequest.AvoidanceHint avoid,
                                                 List<String> tags) {
        return buildRequest(itinerary, avoid, tags, false);
    }

    /**
     * @param skipLlm 표준 4단계 일정(buildStandardDayPlan) 전용 - LLM 태그·문장 생성(Stage4)이
     *                전체 생성 시간의 상당 부분을 차지해(2026-08-16 실측 약 9초/파이프라인) 첫 화면
     *                "당일치기 시작하기"가 오래 걸린다는 사용자 피드백으로 건너뛰게 함. 순위 결정에는
     *                관여하지 않는 단계라 어떤 장소가 뽑히는지는 동일하다.
     */
    private RecommendationRequest buildRequest(Itinerary itinerary,
                                                 RecommendationRequest.AvoidanceHint avoid,
                                                 List<String> tags,
                                                 boolean skipLlm) {
        List<String> excludeContentIds = itinerary.getItems().stream()
                .map(ItineraryItem::getContentId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toList());
        List<String> excludePlaceNames = List.copyOf(tripRecordService.getBadPlaceNames(itinerary.getSessionUuid()));
        ItineraryItem origin = originItem(itinerary);

        return RecommendationRequest.builder()
                .regionCode(itinerary.getSignguFullCode())
                .withPet(itinerary.isWithPet())
                .strollerFriendly(itinerary.isStrollerFriendly())
                .accessibleFriendly(itinerary.isAccessibleFriendly())
                .companionType(itinerary.getCompanionType())
                .adultAgeGroup(itinerary.getAdultAgeGroup())
                .childAges(itinerary.getChildAges())
                .tags(tags)
                .excludeContentIds(excludeContentIds)
                .excludePlaceNames(excludePlaceNames)
                .avoidanceHint(avoid)
                .originContentId(origin == null ? null : origin.getContentId())
                .originContentTypeId(origin == null ? null : origin.getContentTypeId())
                .skipLlm(skipLlm)
                .build();
    }

    /**
     * 거리(km)·주변 추천 기준점 - 사용자가 고정(pin)한 장소가 있으면 그걸 최우선으로 삼는다
     * ("여기 근처로 채우고 싶다"는 명시적 의도), 없으면 마지막 담은 장소를 기준으로 삼는다.
     * 여러 곳을 고정했으면 가장 최근(뒤쪽)에 담긴 걸 우선(ItineraryController.originItem과 동일 규칙).
     */
    private ItineraryItem originItem(Itinerary itinerary) {
        List<ItineraryItem> items = itinerary.getItems();
        if (items.isEmpty()) {
            return null;
        }
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).isPinned()) {
                return items.get(i);
            }
        }
        return items.get(items.size() - 1);
    }

    private SmartPlanResponse assemble(List<RecommendationCandidate> attractionsRaw,
                                       List<RecommendationCandidate> foodRaw,
                                       List<LocalDate> targetDays,
                                       CompanionType companion,
                                       int placeCap,
                                       boolean rain, boolean heat) {
        List<RecommendationCandidate> attractions = sortPopular(attractionsRaw);
        List<RecommendationCandidate> foods = sortPopular(foodRaw);
        // 맛집 풀이 비면 관광 풀에서 음식 후보를 분리
        if (foods.isEmpty()) {
            List<RecommendationCandidate> splitFood = new ArrayList<>();
            List<RecommendationCandidate> splitAttr = new ArrayList<>();
            for (RecommendationCandidate c : attractions) {
                if (isFoodCandidate(c)) {
                    splitFood.add(c);
                } else {
                    splitAttr.add(c);
                }
            }
            if (!splitFood.isEmpty()) {
                foods = splitFood;
                attractions = splitAttr;
            }
        } else {
            Set<String> foodIds = foods.stream()
                    .map(RecommendationCandidate::getContentId)
                    .filter(id -> id != null)
                    .collect(Collectors.toSet());
            attractions = attractions.stream()
                    .filter(c -> c.getContentId() == null || !foodIds.contains(c.getContentId()))
                    .filter(c -> !isFoodCandidate(c))
                    .collect(Collectors.toCollection(ArrayList::new));
        }

        boolean crowdFiltered = attractionsRaw.stream()
                .anyMatch(c -> CrowdCongestionEvaluator.fromPeakRelativeRate(c.getCrowdRate()).isTriggered());

        List<RecommendationCandidate> attrPool = new ArrayList<>(attractions);
        List<RecommendationCandidate> foodPool = new ArrayList<>(foods);
        List<SmartPlanResponse.DayPlan> days = new ArrayList<>();
        List<RecommendationCandidate> allStops = new ArrayList<>();
        double totalKm = 0;
        int globalRank = 1;
        boolean familyPace = isFamilyPace(companion);

        for (int d = 0; d < targetDays.size(); d++) {
            LocalDate date = targetDays.get(d);
            LocalTime dayStart = resolveDayStart(date);
            List<RecommendationCandidate> routed = buildDayRhythm(
                    attrPool, foodPool, dayStart, familyPace, placeCap);

            fillDistances(routed);
            double dayKm = RouteOptimizer.totalDistanceKm(routed);
            totalKm += dayKm;

            for (RecommendationCandidate stop : routed) {
                stop.setVisitDate(date.toString());
                stop.setRank(globalRank++);
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

        boolean sameDayPlan = targetDays.size() == 1 && targetDays.get(0).equals(KoreaClock.today());
        String summary = buildSummary(rain, heat, crowdFiltered, totalKm, allStops, targetDays.size(),
                sameDayPlan ? resolveDayStart(targetDays.get(0)) : null, familyPace);
        log.info("[SmartPlan] days={}, stops={}, meals={}, family={}, rain={}, heat={}, totalKm={}",
                targetDays.size(), allStops.size(),
                allStops.stream().filter(this::isMealStop).count(),
                familyPace, rain, heat, totalKm);

        return SmartPlanResponse.builder()
                .strategySummary(summary)
                .weatherAdjusted(rain)
                .heatAdjusted(heat)
                .crowdFiltered(crowdFiltered)
                .estimatedTotalDistanceKm(Math.round(totalKm * 10.0) / 10.0)
                .candidateCount(attractionsRaw.size() + foodRaw.size())
                .visitDate(targetDays.size() == 1 ? targetDays.get(0).toString() : null)
                .dayCount(targetDays.size())
                .days(days)
                .stops(allStops)
                .build();
    }

    /**
     * 현실적인 당일치기: 오전 1~2 · 점심 · 오후 1~2 · 저녁 · (선택) 저녁 후 — 최대 7슬롯.
     * 각 구간의 "1번째"는 기존과 동일하게 시간창·예산만으로 무조건 시도한다(회귀 없음). "2번째"
     * (오전2/오후2/저녁후)는 방금 배치한 곳에서 가까운 후보가 남아 있을 때만 밀도+거리 기준으로
     * 추가한다 — 슬롯 개수를 늘리려고 먼 곳을 억지로 넣지 않는다는 요구사항(isNearby).
     * 가족은 오후 1곳(2번째 없음), 저녁 후는 생략해 여유를 둔다.
     */
    List<RecommendationCandidate> buildDayRhythm(List<RecommendationCandidate> attrPool,
                                                   List<RecommendationCandidate> foodPool,
                                                   LocalTime dayStart,
                                                   boolean familyPace,
                                                   int placeCap) {
        List<RecommendationCandidate> day = new ArrayList<>();
        if (dayStart == null || dayStart.isAfter(LATEST_START)) {
            return day;
        }
        LocalTime cursor = dayStart;
        int attractionStay = familyPace ? FAMILY_ATTRACTION_STAY : ATTRACTION_STAY;
        int afternoonLimit = familyPace ? 1 : 2;
        int attractionBudget = placeCap > 0
                ? placeCap
                : (familyPace ? 3 : 5); // 오전1~2+오후1~2+(저녁후0~1)
        int attractionsUsed = 0;
        RecommendationCandidate prev = null;

        // 1) 오전 관광 1~2곳 (11:30 이전 시작 가능할 때, 2번째는 밀도+거리 통과 시에만)
        if (cursor.isBefore(LocalTime.of(11, 30)) && attractionsUsed < attractionBudget) {
            RecommendationCandidate morning = takeMostPopularInWindow(attrPool);
            if (morning != null) {
                placeStop(morning, cursor, "오전 1", false);
                attachBackup(morning, attrPool);
                day.add(morning);
                attractionsUsed++;
                prev = morning;
                cursor = cursor.plusMinutes(attractionStay + travelMinutes(prev, peekMostPopular(attrPool)));

                if (cursor.isBefore(LocalTime.of(11, 30)) && attractionsUsed < attractionBudget
                        && isNearby(prev, peekMostPopular(attrPool))) {
                    RecommendationCandidate morning2 = takeMostPopularInWindow(attrPool);
                    if (morning2 != null) {
                        placeStop(morning2, cursor, "오전 2", false);
                        attachBackup(morning2, attrPool);
                        day.add(morning2);
                        attractionsUsed++;
                        prev = morning2;
                        cursor = cursor.plusMinutes(attractionStay + travelMinutes(prev, peekNearest(attrPool, prev)));
                    }
                }
            }
        }

        // 2) 점심 (14:00 전)
        if (!cursor.isAfter(LocalTime.of(14, 0))) {
            LocalTime lunchTime = maxTime(cursor, LUNCH_ANCHOR);
            if (!lunchTime.isAfter(LocalTime.of(14, 0))) {
                RecommendationCandidate lunch = takeNearest(foodPool, prev);
                if (lunch != null) {
                    placeStop(lunch, lunchTime, "🍽️ 점심", true);
                    attachBackup(lunch, foodPool);
                    day.add(lunch);
                    prev = lunch;
                    cursor = lunchTime.plusMinutes(MEAL_STAY + DEFAULT_TRAVEL_MINUTES);
                }
            }
        }

        // 3) 오후 관광 1~2곳 (17:30 전) - 1번째는 기존과 동일하게 무조건 시도, 2번째만 밀도+거리 게이팅
        int afternoonLeft = afternoonLimit;
        boolean firstAfternoon = true;
        while (afternoonLeft > 0 && attractionsUsed < attractionBudget && cursor.isBefore(LocalTime.of(17, 30))) {
            if (!firstAfternoon && !isNearby(prev, peekNearest(attrPool, prev))) {
                break;
            }
            RecommendationCandidate afternoon = takeNearest(attrPool, prev);
            if (afternoon == null) {
                break;
            }
            if (cursor.isAfter(LATEST_START)) {
                attrPool.add(0, afternoon);
                break;
            }
            placeStop(afternoon, cursor, firstAfternoon ? "오후 1" : "오후 2", false);
            attachBackup(afternoon, attrPool);
            day.add(afternoon);
            attractionsUsed++;
            afternoonLeft--;
            firstAfternoon = false;
            prev = afternoon;
            cursor = cursor.plusMinutes(attractionStay + DEFAULT_TRAVEL_MINUTES);
        }

        // 4) 저녁 (19:30 전)
        if (!cursor.isAfter(LocalTime.of(19, 30))) {
            LocalTime dinnerTime = maxTime(cursor, DINNER_ANCHOR);
            if (!dinnerTime.isAfter(LocalTime.of(19, 30))) {
                RecommendationCandidate dinner = takeNearest(foodPool, prev);
                if (dinner != null) {
                    placeStop(dinner, dinnerTime, "🍽️ 저녁", true);
                    attachBackup(dinner, foodPool);
                    day.add(dinner);
                    prev = dinner;
                    cursor = dinnerTime.plusMinutes(MEAL_STAY + DEFAULT_TRAVEL_MINUTES);
                }
            }
        }

        // 5) 저녁 후 선택 1곳 — 가족은 생략(여유), 솔로/커플만 + 밀도+거리 통과 시에만
        if (!familyPace && attractionsUsed < attractionBudget && !cursor.isAfter(LocalTime.of(19, 45))
                && isNearby(prev, peekNearest(attrPool, prev))) {
            RecommendationCandidate evening = takeNearest(attrPool, prev);
            if (evening != null && !cursor.isAfter(LATEST_START)) {
                placeStop(evening, cursor.isBefore(LocalTime.of(19, 30)) ? LocalTime.of(19, 30) : cursor,
                        "저녁 후 일정", false);
                attachBackup(evening, attrPool);
                day.add(evening);
            } else if (evening != null) {
                attrPool.add(0, evening);
            }
        }

        return day.stream()
                .filter(s -> {
                    LocalTime t = parseTime(s.getSuggestedTime());
                    return t != null && !t.isAfter(LATEST_START);
                })
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * 방금 배치한 스탑 바로 다음으로 가까운 후보를 풀에서 "제거하지 않고" 예비 후보로 참조만 남긴다.
     * 다른 슬롯이 이 후보를 더 잘 쓸 수도 있어(예: 오후의 예비가 저녁후의 대표가 될 수도) 지금 당장
     * 소모하지 않는다 - 사용자가 대표를 삭제했을 때만(ItineraryService.deleteItem) 실제로 쓰인다.
     */
    private void attachBackup(RecommendationCandidate placed, List<RecommendationCandidate> pool) {
        RecommendationCandidate backup = peekNearest(pool, placed);
        if (backup != null) {
            placed.setBackupContentId(backup.getContentId());
            placed.setBackupContentTypeId(backup.getContentTypeId());
            placed.setBackupPlaceName(backup.getPlaceName());
        }
    }

    /** 슬롯 확장(2번째 오전/오후, 저녁후) 판단 기준 - anchor와 candidate가 근접 반경 안일 때만 확장 */
    private boolean isNearby(RecommendationCandidate anchor, RecommendationCandidate candidate) {
        if (anchor == null || candidate == null) {
            return false;
        }
        Double km = GeoUtils.distanceKmSafe(anchor.getMapX(), anchor.getMapY(), candidate.getMapX(), candidate.getMapY());
        return km != null && km <= SLOT_EXPANSION_NEAR_KM;
    }

    private void placeStop(RecommendationCandidate stop, LocalTime time, String slotLabel, boolean meal) {
        placeStop(stop, time, slotLabel, meal ? SlotKind.MEAL : SlotKind.ATTRACTION, false);
    }

    /**
     * @param forced true면 마감 게이트를 넘겨도 슬롯을 비우지 않는다 - 안전 시각으로 당겨보고,
     *               그마저 불가능하면(안전 시각이 하루 시작보다 이르는 등) 요청 시각 그대로 강행한다.
     */
    private void placeStop(RecommendationCandidate stop, LocalTime time, String slotLabel, SlotKind kind, boolean forced) {
        LocalTime close = ClosingTimeGate.parseHhMm(stop.getCloseTime());
        if (close == null) {
            close = BusinessHoursEvaluator.extractCloseTimeFromText(stop.getUseTimeText());
        }
        LocalTime visit = time;
        ClosingTimeGate.CheckResult check = ClosingTimeGate.check(close, visit);
        if (check.blocked()) {
            LocalTime safe = close.minusMinutes(BusinessHoursEvaluator.CLOSE_BUFFER_MINUTES + 1L);
            boolean safeWorks = !safe.isBefore(DAY_START) && !ClosingTimeGate.check(close, safe).blocked();
            if (safeWorks) {
                visit = safe;
            } else if (forced) {
                visit = time;
            } else {
                stop.setSuggestedTime(null);
                return;
            }
        }
        stop.setSuggestedTime(visit.format(TIME_FORMAT));
        if (kind == SlotKind.MEAL) {
            stop.setCategory(slotLabel.contains("점심") ? "점심" : "저녁");
            stop.setMatchedTags(List.of("#맛집"));
            stop.setOneLiner(slotLabel + " · 여유롭게 식사해요");
        } else if (kind == SlotKind.CAFE) {
            stop.setCategory("카페");
            stop.setMatchedTags(List.of("#카페"));
            stop.setOneLiner(slotLabel + " · 잠깐 쉬어가요");
        } else {
            if (stop.getCategory() == null || stop.getCategory().isBlank()
                    || "점심".equals(stop.getCategory()) || "저녁".equals(stop.getCategory())
                    || "카페".equals(stop.getCategory())) {
                stop.setCategory(slotLabel);
            }
            stop.setMatchedTags(PlaceTagSanitizer.sanitize(
                    stop.getMatchedTags(),
                    stop.getContentTypeId(),
                    stop.getPlaceName(),
                    stop.getCategory(),
                    null));
            if (slotLabel.contains("오전")
                    && CrowdCongestionEvaluator.fromPeakRelativeRate(stop.getCrowdRate()).isTriggered()) {
                stop.setOneLiner("인기 명소 · 오전에 가면 비교적 한산해요");
            } else if (stop.getOneLiner() == null || stop.getOneLiner().isBlank()) {
                stop.setOneLiner(slotLabel + " · " + defaultLine(stop));
            } else if (!stop.getOneLiner().contains("오전") && !stop.getOneLiner().contains("오후")
                    && !stop.getOneLiner().contains("저녁") && !stop.getOneLiner().contains("카페")) {
                stop.setOneLiner(slotLabel + " · " + stop.getOneLiner());
            }
        }
    }

    /** 개인화 순서 보존 윈도우 - takeNearest가 이 안에서만 최단거리를 고른다(ProximityRanking과 같은 취지) */
    static final int PERSONALIZATION_WINDOW = 6;

    /**
     * 혼잡도 "값"으로 완전히 재정렬하면 RecommendationPipeline이 이미 계산한 개인화 순서(동반유형/
     * 유모차·무장애/연령대)가 사라진다(2026-08-20 사용자 제보 - 대가족 여행에 근대역사관 대신
     * 남농기념관이 뽑힌 원인 중 하나). ProximityRanking/ReservationRequiredRanking과 동일한 설계로
     * 바꿔 혼잡 트리거 걸린 후보만 뒤 버킷으로 밀고, 같은 버킷 안에서는 들어온 순서(=개인화 순서)를
     * 안정정렬로 그대로 유지한다. 버킷 방식이라 후보를 제거하지 않아, 기존에 있던 "comfortable이
     * 부족하면 raw로 폴백" 로직은 더 이상 필요 없다.
     */
    List<RecommendationCandidate> sortComfortable(List<RecommendationCandidate> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        List<RecommendationCandidate> pool = new ArrayList<>(raw);
        pool.sort(Comparator
                .comparingInt((RecommendationCandidate c) -> crowdBucket(c))
                .thenComparing(c -> c.getThumbnailUrl() == null || c.getThumbnailUrl().isBlank() ? 1 : 0));
        int cap = Math.min(pool.size(), 24);
        return new ArrayList<>(pool.subList(0, cap));
    }

    /**
     * 인기(집중률) 높은 순. 혼잡하다고 뒤로 밀지 않는다 — 스마트 동선은 인기 명소를 살리고
     * 오전에 배치한다. 집중률 없는 후보는 뒤로, 같은 값이면 들어온 순서를 유지한다.
     */
    List<RecommendationCandidate> sortPopular(List<RecommendationCandidate> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        List<RecommendationCandidate> pool = new ArrayList<>(raw);
        pool.sort(Comparator
                .comparing(RecommendationCandidate::getCrowdRate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(c -> c.getThumbnailUrl() == null || c.getThumbnailUrl().isBlank() ? 1 : 0));
        int cap = Math.min(pool.size(), 24);
        return new ArrayList<>(pool.subList(0, cap));
    }

    private static int crowdBucket(RecommendationCandidate c) {
        return CrowdCongestionEvaluator.fromPeakRelativeRate(c.getCrowdRate()).isTriggered() ? 1 : 0;
    }

    /**
     * 오전 슬롯용 - 개인화 윈도우 안에서 집중률(인기)이 가장 높은 곳을 고른다.
     * 붐비는 인기 명소를 빼지 않고, 상대적으로 한산한 오전에 넣기 위함.
     */
    RecommendationCandidate takeMostPopularInWindow(List<RecommendationCandidate> pool) {
        RecommendationCandidate chosen = peekMostPopular(pool);
        if (chosen == null) {
            return null;
        }
        pool.remove(chosen);
        return chosen;
    }

    RecommendationCandidate peekMostPopular(List<RecommendationCandidate> pool) {
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        int windowSize = Math.min(PERSONALIZATION_WINDOW, pool.size());
        int bestIdx = 0;
        double bestRate = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < windowSize; i++) {
            Double rate = pool.get(i).getCrowdRate();
            double v = rate == null ? Double.NEGATIVE_INFINITY : rate;
            if (v > bestRate) {
                bestRate = v;
                bestIdx = i;
            }
        }
        return pool.get(bestIdx);
    }

    /**
     * pool 앞쪽 윈도우(개인화 순서가 가장 강하게 반영된 구간)에서만 최단거리를 고른다 - 전체 풀을
     * 스캔해 순수 거리로만 고르면 개인화 순서가 무의미해지므로(위 sortComfortable 주석 참고),
     * "개인화가 먼저 후보를 추리고 그 안에서 동선 효율을 본다"는 순서로 바꾼다. 윈도우 안 후보가
     * 이미 다른 슬롯에 다 쓰였으면(호출부에서 remove) 전체 풀로 넓혀 슬롯은 무조건 채운다.
     */
    RecommendationCandidate takeNearest(List<RecommendationCandidate> pool, RecommendationCandidate origin) {
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        if (origin == null || origin.getMapX() == null || origin.getMapY() == null) {
            return pool.remove(0);
        }
        int windowSize = Math.min(PERSONALIZATION_WINDOW, pool.size());
        int bestIdx = 0;
        double bestKm = Double.POSITIVE_INFINITY;
        for (int i = 0; i < windowSize; i++) {
            RecommendationCandidate c = pool.get(i);
            Double km = GeoUtils.distanceKmSafe(origin.getMapX(), origin.getMapY(), c.getMapX(), c.getMapY());
            double d = km == null ? 50.0 : km;
            if (d < bestKm) {
                bestKm = d;
                bestIdx = i;
            }
        }
        RecommendationCandidate chosen = pool.remove(bestIdx);
        if (bestKm < Double.POSITIVE_INFINITY && bestKm < 50) {
            chosen.setDistanceKm(Math.round(bestKm * 10.0) / 10.0);
        }
        return chosen;
    }

    private RecommendationCandidate peekNearest(List<RecommendationCandidate> pool, RecommendationCandidate origin) {
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        if (origin == null) {
            return pool.get(0);
        }
        RecommendationCandidate best = pool.get(0);
        double bestKm = Double.POSITIVE_INFINITY;
        for (RecommendationCandidate c : pool) {
            Double km = GeoUtils.distanceKmSafe(origin.getMapX(), origin.getMapY(), c.getMapX(), c.getMapY());
            double d = km == null ? 50.0 : km;
            if (d < bestKm) {
                bestKm = d;
                best = c;
            }
        }
        return best;
    }

    private int travelMinutes(RecommendationCandidate from, RecommendationCandidate to) {
        if (from == null || to == null) {
            return DEFAULT_TRAVEL_MINUTES;
        }
        Double km = GeoUtils.distanceKmSafe(from.getMapX(), from.getMapY(), to.getMapX(), to.getMapY());
        if (km == null) {
            return DEFAULT_TRAVEL_MINUTES;
        }
        int travel = (int) Math.ceil(km * MINUTES_PER_KM);
        return Math.max(10, Math.min(travel, 90));
    }

    private void fillDistances(List<RecommendationCandidate> routed) {
        for (int i = 0; i < routed.size(); i++) {
            if (i == 0) {
                continue;
            }
            RecommendationCandidate prev = routed.get(i - 1);
            RecommendationCandidate cur = routed.get(i);
            Double km = GeoUtils.distanceKmSafe(prev.getMapX(), prev.getMapY(), cur.getMapX(), cur.getMapY());
            if (km != null) {
                cur.setDistanceKm(Math.round(km * 10.0) / 10.0);
            }
        }
    }

    static boolean isFamilyPace(CompanionType companion) {
        return companion == CompanionType.FAMILY_4 || companion == CompanionType.EXTENDED_FAMILY;
    }

    private boolean isFoodCandidate(RecommendationCandidate c) {
        if (c == null) {
            return false;
        }
        return PlaceTagSanitizer.looksLikeFood(c.getContentTypeId(), c.getPlaceName(), c.getCategory());
    }

    private boolean isCafeCandidate(RecommendationCandidate c) {
        if (c == null) {
            return false;
        }
        return PlaceTagSanitizer.looksLikeCafe(c.getContentTypeId(), c.getPlaceName(), c.getCategory(), c.getMatchedTags());
    }

    private boolean isMealStop(RecommendationCandidate c) {
        String cat = c.getCategory() == null ? "" : c.getCategory();
        return "점심".equals(cat) || "저녁".equals(cat)
                || (c.getOneLiner() != null && c.getOneLiner().contains("🍽️"));
    }

    LocalTime resolveDayStart(LocalDate visitDate) {
        if (visitDate != null && visitDate.equals(KoreaClock.today())) {
            LocalTime soon = KoreaClock.nowTime().plusMinutes(TODAY_LEAD_MINUTES).withSecond(0).withNano(0);
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

    /** 테스트·하위 호환: 단순 시간창 기준 상한 */
    int maxStopsForWindow(LocalTime start) {
        if (start == null || !start.isBefore(DAY_END) || start.isAfter(LATEST_START)) {
            return 0;
        }
        long minutes = ChronoUnit.MINUTES.between(start, DAY_END);
        int slot = ATTRACTION_STAY + DEFAULT_TRAVEL_MINUTES;
        int max = (int) (minutes / slot);
        if (max <= 0) {
            return start.isAfter(LATEST_START) ? 0 : 1;
        }
        return Math.min(Math.max(max, 1), 5);
    }

    private static LocalTime maxTime(LocalTime a, LocalTime b) {
        return a.isAfter(b) ? a : b;
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
                                List<RecommendationCandidate> stops, int dayCount,
                                LocalTime todayStart, boolean familyPace) {
        List<String> parts = new ArrayList<>();
        long meals = stops.stream().filter(this::isMealStop).count();
        long cafes = stops.stream().filter(s -> "카페".equals(s.getCategory())).count();
        long sights = stops.size() - meals - cafes;
        if (dayCount > 1) {
            parts.add(dayCount + "일 일정");
        }
        if (todayStart != null) {
            parts.add("오늘 " + todayStart.format(TIME_FORMAT) + "부터");
        }
        if (familyPace) {
            parts.add("가족 여유 코스");
        }
        parts.add("관광 " + sights + "곳 · 식사 " + meals + "끼" + (cafes > 0 ? " · 카페 " + cafes : ""));
        parts.add("오전→점심→오후→카페→저녁 리듬");
        if (totalKm > 0) {
            parts.add(String.format("약 %.1fkm", totalKm));
        }
        if (crowdFiltered) {
            parts.add("인기 명소는 오전에");
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
            if (CrowdCongestionEvaluator.fromPeakRelativeRate(c.getCrowdRate()).isTriggered()) {
                return "인기 명소";
            }
            return String.format("여유율 %.0f%%", 100 - c.getCrowdRate());
        }
        return "동선에 맞춰 이어져요";
    }
}
