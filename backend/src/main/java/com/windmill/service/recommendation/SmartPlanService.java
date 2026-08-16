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
 * 1) 혼잡도 낮은 순으로 고르고
 * 2) 날씨(비/폭염)면 실내 코스로 전환하며
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

    private final RecommendationPipeline recommendationPipeline;
    private final TriggerScheduler triggerScheduler;
    private final RegionCodeService regionCodeService;
    private final TripRecordService tripRecordService;

    public Mono<SmartPlanResponse> build(Itinerary itinerary, int placeCount) {
        return build(itinerary, placeCount, null);
    }

    /**
     * "당일치기 시작하기" 전용 표준 4단계 일정 - 아침 일정 → 점심 식사 → 오후 일정 → 저녁 식사를
     * 무조건 채운다(사용자 요구사항: 후보 풀이 빡빡하거나 마감이 애매해도 슬롯을 비우지 않음).
     * 기존 {@link #build}의 유연한 시간창 판정(현재 시각 기준 시작·후보 소진 시 슬롯 생략)은 여행 중
     * "다시 짜기"에는 맞지만, 최초 생성 직후엔 "지금 몇 시든 하루 전체 틀"을 원한다는 게 이번 요구라
     * 09:00/12:00/14:30/18:00 고정 슬롯의 별도 경로로 분리했다. 개인화(연령대/자녀나이/반려동물/유모차/
     * 무장애)는 기존 {@link #buildRequest}를 그대로 재사용해 동일하게 반영된다.
     */
    public Mono<SmartPlanResponse> buildStandardDayPlan(Itinerary itinerary) {
        RegionCode region = regionCodeService.find(itinerary.getSignguFullCode())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지역코드: " + itinerary.getSignguFullCode()));
        LocalDate date = itinerary.getStartDate() != null ? itinerary.getStartDate() : KoreaClock.today();
        CompanionType companion = itinerary.getCompanionType();
        boolean familyPace = isFamilyPace(companion);

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

                    List<String> attractionTags = familyPace
                            ? List.of("#실내", "#자연", "#아이동반")
                            : List.of("#실내", "#자연", "#역사");
                    RecommendationRequest attractionReq = buildRequest(itinerary, avoid, attractionTags);
                    RecommendationRequest foodReq = buildRequest(itinerary, avoid, List.of("#맛집"));

                    return Mono.zip(
                                    recommendationPipeline.recommend(attractionReq).onErrorReturn(List.of()),
                                    recommendationPipeline.recommend(foodReq).onErrorReturn(List.of()))
                            .map(tuple -> assembleStandardDay(tuple.getT1(), tuple.getT2(), date, companion, rain, heat));
                });
    }

    private SmartPlanResponse assembleStandardDay(List<RecommendationCandidate> attractionsRaw,
                                                    List<RecommendationCandidate> foodRaw,
                                                    LocalDate date, CompanionType companion,
                                                    boolean rain, boolean heat) {
        List<RecommendationCandidate> attrPool = new ArrayList<>(sortComfortable(attractionsRaw));
        List<RecommendationCandidate> foodPool = new ArrayList<>(sortComfortable(foodRaw));
        // 맛집 풀이 비면(지역 데이터 부족) 관광 풀에서 음식 후보를 분리해 최소한 식사 슬롯을 채운다
        if (foodPool.isEmpty()) {
            List<RecommendationCandidate> splitFood = new ArrayList<>();
            List<RecommendationCandidate> splitAttr = new ArrayList<>();
            for (RecommendationCandidate c : attrPool) {
                (isFoodCandidate(c) ? splitFood : splitAttr).add(c);
            }
            if (!splitFood.isEmpty()) {
                foodPool = splitFood;
                attrPool = splitAttr;
            }
        }

        List<RecommendationCandidate> day = new ArrayList<>();
        RecommendationCandidate prev = null;
        // 관광 풀과 맛집 풀을 별도 API 호출로 채우기 때문에 같은 장소가 두 풀 모두에 겹쳐 들어올 수 있다
        // (예: 특정 장소가 두 테마 조회 모두에 매칭). 슬롯마다 고르기 직전에 이미 오늘 담긴 contentId를
        // 상대편 풀에서도 제거해 같은 장소가 두 번 배치되는 걸 막는다.
        Set<String> usedContentIds = new java.util.HashSet<>();

        RecommendationCandidate morning = placeForced(attrPool, prev, LocalTime.of(9, 0), "아침 일정", false);
        if (morning != null) {
            day.add(morning);
            prev = morning;
            addUsed(usedContentIds, morning);
            removeUsed(foodPool, usedContentIds);
        }

        RecommendationCandidate lunch = placeForced(foodPool, prev, LocalTime.of(12, 0), "점심 식사", true);
        if (lunch != null) {
            day.add(lunch);
            prev = lunch;
            addUsed(usedContentIds, lunch);
            removeUsed(attrPool, usedContentIds);
        }

        RecommendationCandidate afternoon = placeForced(attrPool, prev, LocalTime.of(14, 30), "오후 일정", false);
        if (afternoon != null) {
            day.add(afternoon);
            prev = afternoon;
            addUsed(usedContentIds, afternoon);
            removeUsed(foodPool, usedContentIds);
        }

        RecommendationCandidate dinner = placeForced(foodPool, prev, LocalTime.of(18, 0), "저녁 식사", true);
        if (dinner != null) {
            day.add(dinner);
        }

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

        log.info("[SmartPlan] 표준 4단계 일정 생성 - date={}, stops={}, family={}", date, day.size(), isFamilyPace(companion));

        return SmartPlanResponse.builder()
                .strategySummary(summary)
                .weatherAdjusted(rain)
                .heatAdjusted(heat)
                .crowdFiltered(crowdFiltered)
                .estimatedTotalDistanceKm(Math.round(dayKm * 10.0) / 10.0)
                .candidateCount(attractionsRaw.size() + foodRaw.size())
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

    private static void addUsed(Set<String> usedContentIds, RecommendationCandidate candidate) {
        if (candidate.getContentId() != null) {
            usedContentIds.add(candidate.getContentId());
        }
    }

    private static void removeUsed(List<RecommendationCandidate> pool, Set<String> usedContentIds) {
        pool.removeIf(c -> c.getContentId() != null && usedContentIds.contains(c.getContentId()));
    }

    /**
     * pool에서 가장 가까운 후보 하나를 골라 targetTime에 강제 배치한다(무조건 채우기 - 마감 게이트에
     * 걸려도 안전 시각으로 당기거나, 그마저 불가능하면 요청 시각 그대로 강행한다). pool이 비어 있으면 null.
     */
    private RecommendationCandidate placeForced(List<RecommendationCandidate> pool, RecommendationCandidate origin,
                                                  LocalTime targetTime, String slotLabel, boolean meal) {
        RecommendationCandidate candidate = takeNearest(pool, origin);
        if (candidate == null) {
            return null;
        }
        placeStop(candidate, targetTime, slotLabel, meal, true);
        return candidate;
    }

    /**
     * @param forDate null이면 여행 기간 전체(최대 7일), 지정 시 해당 일자만
     * @param placeCount 하위 호환용(0이면 동행 유형별 자동). 명시 시 관광 상한으로만 참고.
     */
    public Mono<SmartPlanResponse> build(Itinerary itinerary, int placeCount, LocalDate forDate) {
        RegionCode region = regionCodeService.find(itinerary.getSignguFullCode())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지역코드: " + itinerary.getSignguFullCode()));

        List<LocalDate> targetDays = resolveDays(itinerary, forDate);
        CompanionType companion = itinerary.getCompanionType();
        boolean familyPace = isFamilyPace(companion);

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

                    List<String> attractionTags = familyPace
                            ? List.of("#실내", "#자연", "#아이동반")
                            : List.of("#실내", "#자연", "#역사");
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
        List<RecommendationCandidate> attractions = sortComfortable(attractionsRaw);
        List<RecommendationCandidate> foods = sortComfortable(foodRaw);
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
     * 현실적인 당일치기: 오전 1 · 점심 · 오후 1~2 · 저녁 · (선택) 저녁 후.
     * 가족은 오후 1곳, 저녁 후는 생략해 여유를 둔다.
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
                : (familyPace ? 3 : 4); // 오전1+오후1~2+(저녁후0~1)
        int attractionsUsed = 0;
        RecommendationCandidate prev = null;

        // 1) 오전 관광 1곳 (11:30 이전 시작 가능할 때)
        if (cursor.isBefore(LocalTime.of(11, 30)) && attractionsUsed < attractionBudget) {
            RecommendationCandidate morning = takeNearest(attrPool, prev);
            if (morning != null) {
                placeStop(morning, cursor, "오전 일정", false);
                day.add(morning);
                attractionsUsed++;
                prev = morning;
                cursor = cursor.plusMinutes(attractionStay + travelMinutes(prev, peekNearest(attrPool, prev)));
            }
        }

        // 2) 점심 (14:00 전)
        if (!cursor.isAfter(LocalTime.of(14, 0))) {
            LocalTime lunchTime = maxTime(cursor, LUNCH_ANCHOR);
            if (!lunchTime.isAfter(LocalTime.of(14, 0))) {
                RecommendationCandidate lunch = takeNearest(foodPool, prev);
                if (lunch != null) {
                    placeStop(lunch, lunchTime, "🍽️ 점심", true);
                    day.add(lunch);
                    prev = lunch;
                    cursor = lunchTime.plusMinutes(MEAL_STAY + DEFAULT_TRAVEL_MINUTES);
                }
            }
        }

        // 3) 오후 관광 1~2곳 (17:30 전)
        int afternoonLeft = afternoonLimit;
        while (afternoonLeft > 0 && attractionsUsed < attractionBudget && cursor.isBefore(LocalTime.of(17, 30))) {
            RecommendationCandidate afternoon = takeNearest(attrPool, prev);
            if (afternoon == null) {
                break;
            }
            if (cursor.isAfter(LATEST_START)) {
                attrPool.add(0, afternoon);
                break;
            }
            placeStop(afternoon, cursor, "오후 일정", false);
            day.add(afternoon);
            attractionsUsed++;
            afternoonLeft--;
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
                    day.add(dinner);
                    prev = dinner;
                    cursor = dinnerTime.plusMinutes(MEAL_STAY + DEFAULT_TRAVEL_MINUTES);
                }
            }
        }

        // 5) 저녁 후 선택 1곳 — 가족은 생략(여유), 솔로/커플만
        if (!familyPace && attractionsUsed < attractionBudget && !cursor.isAfter(LocalTime.of(19, 45))) {
            RecommendationCandidate evening = takeNearest(attrPool, prev);
            if (evening != null && !cursor.isAfter(LATEST_START)) {
                placeStop(evening, cursor.isBefore(LocalTime.of(19, 30)) ? LocalTime.of(19, 30) : cursor,
                        "저녁 후 일정", false);
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

    private void placeStop(RecommendationCandidate stop, LocalTime time, String slotLabel, boolean meal) {
        placeStop(stop, time, slotLabel, meal, false);
    }

    /**
     * @param forced true면 마감 게이트를 넘겨도 슬롯을 비우지 않는다 - 안전 시각으로 당겨보고,
     *               그마저 불가능하면(안전 시각이 하루 시작보다 이르는 등) 요청 시각 그대로 강행한다.
     *               "당일치기 시작하기"의 표준 4단계 일정처럼 슬롯을 무조건 채워야 하는 경로에서만 사용.
     */
    private void placeStop(RecommendationCandidate stop, LocalTime time, String slotLabel, boolean meal, boolean forced) {
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
                // 마감에 맞춰 넣을 수 없으면 suggestedTime을 비워 이후 필터에서 탈락
                stop.setSuggestedTime(null);
                return;
            }
        }
        stop.setSuggestedTime(visit.format(TIME_FORMAT));
        if (meal) {
            stop.setCategory(slotLabel.contains("점심") ? "점심" : "저녁");
            stop.setMatchedTags(List.of("#맛집"));
            stop.setOneLiner(slotLabel + " · 여유롭게 식사해요");
        } else {
            if (stop.getCategory() == null || stop.getCategory().isBlank()
                    || "점심".equals(stop.getCategory()) || "저녁".equals(stop.getCategory())) {
                stop.setCategory(slotLabel);
            }
            stop.setMatchedTags(PlaceTagSanitizer.sanitize(
                    stop.getMatchedTags(),
                    stop.getContentTypeId(),
                    stop.getPlaceName(),
                    stop.getCategory(),
                    null));
            if (stop.getOneLiner() == null || stop.getOneLiner().isBlank()) {
                stop.setOneLiner(slotLabel + " · " + defaultLine(stop));
            } else if (!stop.getOneLiner().contains("오전") && !stop.getOneLiner().contains("오후")
                    && !stop.getOneLiner().contains("저녁")) {
                stop.setOneLiner(slotLabel + " · " + stop.getOneLiner());
            }
        }
    }

    private List<RecommendationCandidate> sortComfortable(List<RecommendationCandidate> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        List<RecommendationCandidate> comfortable = raw.stream()
                .filter(c -> !CrowdCongestionEvaluator.fromPeakRelativeRate(c.getCrowdRate()).isTriggered())
                .collect(Collectors.toCollection(ArrayList::new));
        List<RecommendationCandidate> pool = comfortable.size() >= Math.min(3, raw.size())
                ? comfortable
                : new ArrayList<>(raw);
        pool.sort(Comparator
                .comparing((RecommendationCandidate c) -> c.getCrowdRate() == null ? Double.POSITIVE_INFINITY : c.getCrowdRate())
                .thenComparing(c -> c.getThumbnailUrl() == null || c.getThumbnailUrl().isBlank() ? 1 : 0));
        int cap = Math.min(pool.size(), 24);
        return new ArrayList<>(pool.subList(0, cap));
    }

    private RecommendationCandidate takeNearest(List<RecommendationCandidate> pool, RecommendationCandidate origin) {
        if (pool == null || pool.isEmpty()) {
            return null;
        }
        if (origin == null || origin.getMapX() == null || origin.getMapY() == null) {
            return pool.remove(0);
        }
        int bestIdx = 0;
        double bestKm = Double.POSITIVE_INFINITY;
        for (int i = 0; i < pool.size(); i++) {
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
        long sights = stops.size() - meals;
        if (dayCount > 1) {
            parts.add(dayCount + "일 일정");
        }
        if (todayStart != null) {
            parts.add("오늘 " + todayStart.format(TIME_FORMAT) + "부터");
        }
        if (familyPace) {
            parts.add("가족 여유 코스");
        }
        parts.add("관광 " + sights + "곳 · 식사 " + meals + "끼");
        parts.add("오전→점심→오후→저녁 리듬");
        if (totalKm > 0) {
            parts.add(String.format("약 %.1fkm", totalKm));
        }
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
            return String.format("여유율 %.0f%%", 100 - c.getCrowdRate());
        }
        return "동선에 맞춰 이어져요";
    }
}
