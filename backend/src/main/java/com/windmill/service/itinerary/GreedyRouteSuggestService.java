package com.windmill.service.itinerary;

import com.windmill.client.KakaoDirectionsClient;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.BusinessStatus;
import com.windmill.dto.HoursPhase;
import com.windmill.dto.MapRouteRequest;
import com.windmill.dto.SuggestedRouteResponse;
import com.windmill.dto.SuggestedRouteStop;
import com.windmill.service.recommendation.BusinessHoursEvaluator;
import com.windmill.util.ClosingTimeGate;
import com.windmill.util.GeoUtils;
import com.windmill.util.KoreaClock;
import com.windmill.util.TimeConflictGate;
import com.windmill.util.VisitOrderOptimizer;
import com.windmill.util.VisitTiming;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 현재 위치·시각 기준 그리디 재배열 제안. 일정에 쓰지 않는다.
 * 각 단계에서 남은 장소 중 직선거리 상위 {@link #CANDIDATE_LIMIT}곳만 카카오 이동시간을 조회한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GreedyRouteSuggestService {

    /** 단계마다 카카오 길찾기를 칠 최근접 후보 수. 품질과 호출량의 타협(오픈 퀘스천 1). */
    static final int CANDIDATE_LIMIT = 4;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final KakaoDirectionsClient kakaoDirectionsClient;

    public SuggestedRouteResponse suggest(List<ItineraryItem> items, Double originLon, Double originLat) {
        return suggest(items, originLon, originLat, KoreaClock.now());
    }

    SuggestedRouteResponse suggest(List<ItineraryItem> items, Double originLon, Double originLat,
                                   LocalDateTime at) {
        LocalDateTime when = at == null ? KoreaClock.now() : at;
        List<ItineraryItem> current = sortCurrent(items);
        List<SuggestedRouteStop> currentStops = current.stream().map(item -> snapshotCurrent(item, when)).toList();
        if (current.isEmpty()) {
            return SuggestedRouteResponse.builder()
                    .message("담은 장소가 없어요.")
                    .currentStops(List.of())
                    .suggestedStops(List.of())
                    .build();
        }
        if (current.size() < 2) {
            return SuggestedRouteResponse.builder()
                    .message("장소가 하나라 순서를 바꿀 수 없어요.")
                    .currentStops(currentStops)
                    .suggestedStops(currentStops)
                    .build();
        }

        LocalDate visitDate = resolveVisitDate(current, when.toLocalDate());
        LocalTime cursor = resolveStartTime(visitDate, when);
        boolean useOrigin = originLon != null && originLat != null;

        List<ItineraryItem> withCoords = new ArrayList<>();
        List<ItineraryItem> without = new ArrayList<>();
        for (ItineraryItem item : current) {
            if (hasCoords(item)) {
                withCoords.add(item);
            } else {
                without.add(item);
            }
        }

        MapRouteRequest.MapPoint here;
        if (useOrigin) {
            here = MapRouteRequest.MapPoint.builder()
                    .lon(originLon)
                    .lat(originLat)
                    .name("현재 위치")
                    .build();
        } else if (!withCoords.isEmpty()) {
            here = toPoint(withCoords.get(0));
        } else {
            here = MapRouteRequest.MapPoint.builder().lon(0).lat(0).name("unknown").build();
        }

        List<Planned> planned = snapArrivals(greedy(withCoords, without, here, cursor, visitDate, when));
        List<SuggestedRouteStop> suggestedStops = planned.stream().map(this::toStop).toList();

        int hardCount = (int) planned.stream().filter(Planned::hard).count();
        int totalTravel = planned.stream().mapToInt(p -> p.travelFromPrev).sum();
        boolean orderChanged = !itemIds(currentStops).equals(itemIds(suggestedStops));
        boolean timesChanged = timesDiffer(currentStops, suggestedStops);

        String originLonStr = useOrigin ? String.valueOf(originLon) : null;
        String originLatStr = useOrigin ? String.valueOf(originLat) : null;
        List<ItineraryItem> suggestedItems = planned.stream().map(Planned::item).toList();
        double currentKm = VisitOrderOptimizer.pathDistanceKm(
                current.stream().filter(GreedyRouteSuggestService::hasCoords).toList(),
                originLonStr, originLatStr, ItineraryItem::getMapX, ItineraryItem::getMapY);
        double suggestedKm = VisitOrderOptimizer.pathDistanceKm(
                suggestedItems.stream().filter(GreedyRouteSuggestService::hasCoords).toList(),
                originLonStr, originLatStr, ItineraryItem::getMapX, ItineraryItem::getMapY);

        log.info("[GreedySuggest] n={} hardToday={} orderChanged={} gps={} travelMin={}",
                current.size(), hardCount, orderChanged, useOrigin, totalTravel);

        return SuggestedRouteResponse.builder()
                .message(buildMessage(useOrigin, hardCount, orderChanged, timesChanged, totalTravel))
                .currentStops(currentStops)
                .suggestedStops(suggestedStops)
                .orderChanged(orderChanged)
                .timesChanged(timesChanged)
                .hardTodayCount(hardCount)
                .currentDistanceKm(currentKm)
                .suggestedDistanceKm(suggestedKm)
                .totalTravelMinutes(totalTravel)
                .usedGpsOrigin(useOrigin)
                .build();
    }

    private List<Planned> greedy(List<ItineraryItem> withCoords, List<ItineraryItem> without,
                                 MapRouteRequest.MapPoint start, LocalTime startTime,
                                 LocalDate visitDate, LocalDateTime at) {
        List<ItineraryItem> remaining = new ArrayList<>(withCoords);
        List<Planned> out = new ArrayList<>();
        List<TimeConflictGate.Occupant> occupants = new ArrayList<>();
        MapRouteRequest.MapPoint here = start;
        LocalTime cursor = startTime;
        boolean lunchUsed = false;
        boolean dinnerUsed = false;

        while (!remaining.isEmpty()) {
            List<ItineraryItem> ranked = rankByDistance(here, remaining);
            Candidate bestFeasible = pickBestFeasible(
                    here, ranked.subList(0, Math.min(CANDIDATE_LIMIT, ranked.size())),
                    cursor, visitDate, at, lunchUsed, dinnerUsed, occupants);
            if (bestFeasible == null && ranked.size() > CANDIDATE_LIMIT) {
                bestFeasible = pickBestFeasible(
                        here, ranked, cursor, visitDate, at, lunchUsed, dinnerUsed, occupants);
            }

            if (bestFeasible == null) {
                for (ItineraryItem item : ranked) {
                    int travel = estimateTravel(here, item);
                    Eval eval = evaluate(item, cursor, travel, visitDate, at, lunchUsed, dinnerUsed, occupants);
                    String reason = eval.hardReason != null ? eval.hardReason : "CLOSING";
                    out.add(new Planned(item, eval.arrival, travel, true, reason));
                    cursor = eval.arrival.plusMinutes(VisitTiming.stayMinutes(item));
                    here = toPoint(item);
                }
                remaining.clear();
                break;
            }

            Eval pick = bestFeasible.eval;
            remaining.remove(bestFeasible.item);
            out.add(new Planned(bestFeasible.item, pick.arrival, pick.travel, false, null));
            occupants.add(occupantOf(bestFeasible.item, pick.arrival));
            cursor = pick.arrival.plusMinutes(VisitTiming.stayMinutes(bestFeasible.item));
            here = toPoint(bestFeasible.item);
            if (VisitTiming.isMeal(bestFeasible.item)) {
                if (VisitTiming.inLunchWindow(pick.arrival)) {
                    lunchUsed = true;
                } else if (VisitTiming.inDinnerWindow(pick.arrival)) {
                    dinnerUsed = true;
                }
            }
        }

        for (ItineraryItem item : without) {
            int travel = GeoUtils.DEFAULT_TRAVEL_MINUTES;
            LocalTime arrival = cursor.plusMinutes(travel);
            out.add(new Planned(item, arrival, travel, false, null));
            occupants.add(occupantOf(item, arrival));
            cursor = arrival.plusMinutes(VisitTiming.stayMinutes(item));
        }
        return out;
    }

    private Candidate pickBestFeasible(MapRouteRequest.MapPoint here, List<ItineraryItem> candidates,
                                       LocalTime cursor, LocalDate visitDate, LocalDateTime at,
                                       boolean lunchUsed, boolean dinnerUsed,
                                       List<TimeConflictGate.Occupant> occupants) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<ItineraryItem> subset = new ArrayList<>(candidates);
        List<MapRouteRequest.MapPoint> dests = subset.stream().map(this::toPoint).toList();
        int[] minutes = kakaoDirectionsClient.minutesFromOrigin(here, dests);
        Candidate best = null;
        for (int i = 0; i < subset.size(); i++) {
            ItineraryItem item = subset.get(i);
            int travel = travelMinutes(here, item, minutes[i]);
            Eval eval = evaluate(item, cursor, travel, visitDate, at, lunchUsed, dinnerUsed, occupants);
            if (eval.feasible && (best == null || eval.score < best.eval.score
                    || (eval.score == best.eval.score && travel < best.eval.travel))) {
                best = new Candidate(item, eval);
            }
        }
        return best;
    }

    private Eval evaluate(ItineraryItem item, LocalTime cursor, int travel, LocalDate visitDate,
                          LocalDateTime at, boolean lunchUsed, boolean dinnerUsed,
                          List<TimeConflictGate.Occupant> occupants) {
        LocalTime arrival = cursor.plusMinutes(Math.max(0, travel));
        boolean meal = VisitTiming.isMeal(item);
        arrival = nudgeMeal(arrival, meal, lunchUsed, dinnerUsed);
        arrival = waitForOpen(item, arrival);
        String hard = infeasibleReason(item, arrival, visitDate, at, occupants);
        int score = travel + VisitTiming.mealTravelScoreAdjustment(meal, arrival);
        return new Eval(arrival, hard == null, hard, travel, score);
    }

    static LocalTime nudgeMeal(LocalTime arrival, boolean meal, boolean lunchUsed, boolean dinnerUsed) {
        if (!meal || arrival == null) {
            return arrival;
        }
        if (!lunchUsed && !arrival.isAfter(VisitTiming.LUNCH_WINDOW_END)) {
            if (arrival.isBefore(VisitTiming.LUNCH_WINDOW_START)) {
                return VisitTiming.LUNCH_WINDOW_START;
            }
            return arrival;
        }
        if (!dinnerUsed && !arrival.isAfter(VisitTiming.DINNER_WINDOW_END)) {
            if (arrival.isBefore(VisitTiming.DINNER_WINDOW_START)) {
                return VisitTiming.DINNER_WINDOW_START;
            }
        }
        return arrival;
    }

    static LocalTime waitForOpen(ItineraryItem item, LocalTime arrival) {
        if (item == null || arrival == null) {
            return arrival;
        }
        LocalTime open = BusinessHoursEvaluator.extractOpenTimeFromText(item.getUseTimeText());
        if (open != null && arrival.isBefore(open)) {
            return open;
        }
        return arrival;
    }

    static String infeasibleReason(ItineraryItem item, LocalTime arrival, LocalDate visitDate,
                                   LocalDateTime at, List<TimeConflictGate.Occupant> occupants) {
        LocalDate day = visitDate != null ? visitDate : (at == null ? null : at.toLocalDate());
        if (day != null && BusinessHoursEvaluator.isClosedOnRestDate(item.getRestDateText(), day)) {
            return "REST_DAY";
        }
        if (arrival != null && arrival.isAfter(VisitTiming.LATEST_START)) {
            return "TOO_LATE";
        }
        LocalTime close = parsedClose(item);
        int buffer = VisitTiming.closeBufferMinutes(item.getCloseTime(), item.getUseTimeText(),
                item.getDetailFacts());
        if (ClosingTimeGate.check(close, arrival, buffer).blocked()) {
            return "CLOSING";
        }
        Map<String, String> fields = hoursFields(item);
        if (!fields.isEmpty() && day != null && arrival != null) {
            HoursPhase phase = BusinessHoursEvaluator.phaseAt(fields, LocalDateTime.of(day, arrival));
            if (phase == HoursPhase.CLOSED) {
                return "CLOSING";
            }
        }
        LocalTime packEnd = VisitTiming.occupancyEnd(arrival, VisitTiming.packingStayMinutes(item),
                VisitTiming.resolveCloseTime(item));
        if (TimeConflictGate.check(arrival, packEnd, occupants, item.getId()).blocked()) {
            return "TIME_OVERLAP";
        }
        return null;
    }

    static LocalTime parsedClose(ItineraryItem item) {
        LocalTime close = ClosingTimeGate.parseHhMm(item.getCloseTime());
        if (close == null) {
            close = BusinessHoursEvaluator.extractCloseTimeFromText(item.getUseTimeText());
        }
        return close;
    }

    static Map<String, String> hoursFields(ItineraryItem item) {
        Map<String, String> fields = new HashMap<>();
        if (item.getUseTimeText() != null && !item.getUseTimeText().isBlank()) {
            fields.put("usetime", item.getUseTimeText());
        }
        if (item.getRestDateText() != null && !item.getRestDateText().isBlank()) {
            fields.put("restdate", item.getRestDateText());
        }
        return fields;
    }

    private static TimeConflictGate.Occupant occupantOf(ItineraryItem item, LocalTime arrival) {
        LocalTime end = VisitTiming.occupancyEnd(arrival, VisitTiming.packingStayMinutes(item),
                VisitTiming.resolveCloseTime(item));
        return new TimeConflictGate.Occupant(item.getId(), item.getPlaceName(), arrival, end);
    }

    private int travelMinutes(MapRouteRequest.MapPoint from, ItineraryItem to, int kakaoMinutes) {
        if (from == null || !hasCoords(to)) {
            return GeoUtils.DEFAULT_TRAVEL_MINUTES;
        }
        MapRouteRequest.MapPoint dest = toPoint(to);
        if (samePoint(from, dest)) {
            return 0;
        }
        return Math.max(1, kakaoMinutes);
    }

    private int estimateTravel(MapRouteRequest.MapPoint from, ItineraryItem to) {
        if (from == null || !hasCoords(to)) {
            return GeoUtils.DEFAULT_TRAVEL_MINUTES;
        }
        if (samePoint(from, toPoint(to))) {
            return 0;
        }
        return GeoUtils.estimateTravelMinutes(
                String.valueOf(from.getLon()), String.valueOf(from.getLat()),
                to.getMapX(), to.getMapY());
    }

    private static List<ItineraryItem> rankByDistance(MapRouteRequest.MapPoint from, List<ItineraryItem> remaining) {
        List<ItineraryItem> ranked = new ArrayList<>(remaining);
        ranked.sort(Comparator.comparingDouble(item -> {
            Double km = GeoUtils.distanceKmSafe(
                    String.valueOf(from.getLon()), String.valueOf(from.getLat()),
                    item.getMapX(), item.getMapY());
            return km == null ? Double.POSITIVE_INFINITY : km;
        }));
        return ranked;
    }

    private static boolean samePoint(MapRouteRequest.MapPoint a, MapRouteRequest.MapPoint b) {
        if (a == null || b == null) {
            return false;
        }
        return Math.abs(a.getLon() - b.getLon()) < 1e-5 && Math.abs(a.getLat() - b.getLat()) < 1e-5;
    }

    static boolean hasCoords(ItineraryItem item) {
        return item.getMapX() != null && !item.getMapX().isBlank()
                && item.getMapY() != null && !item.getMapY().isBlank();
    }

    private MapRouteRequest.MapPoint toPoint(ItineraryItem item) {
        try {
            return MapRouteRequest.MapPoint.builder()
                    .lon(Double.parseDouble(item.getMapX().trim()))
                    .lat(Double.parseDouble(item.getMapY().trim()))
                    .name(item.getPlaceName())
                    .build();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid coords for " + item.getPlaceName(), e);
        }
    }

    private static List<ItineraryItem> sortCurrent(List<ItineraryItem> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .sorted(Comparator
                        .comparing((ItineraryItem i) -> parseMinutes(i.getScheduledTime()),
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparingInt(ItineraryItem::getDisplayOrder))
                .toList();
    }

    private static Integer parseMinutes(String scheduledTime) {
        LocalTime t = ClosingTimeGate.parseHhMm(scheduledTime);
        return t == null ? null : t.getHour() * 60 + t.getMinute();
    }

    private static LocalDate resolveVisitDate(List<ItineraryItem> items, LocalDate fallback) {
        if (items == null || items.isEmpty()) {
            return fallback;
        }
        ItineraryItem first = items.get(0);
        if (first.getVisitDate() != null) {
            return first.getVisitDate();
        }
        if (first.getItinerary() != null && first.getItinerary().getStartDate() != null) {
            return first.getItinerary().getStartDate();
        }
        return fallback;
    }

    private static LocalTime resolveStartTime(LocalDate visitDate, LocalDateTime at) {
        if (visitDate != null && visitDate.equals(at.toLocalDate())) {
            return at.toLocalTime().withSecond(0).withNano(0);
        }
        return VisitTiming.DAY_START;
    }

    /**
     * "기존 순서" 칸 - 제안(suggestedStops)엔 이미 지금 시각 기준 휴무·영업종료 경고가 붙는데,
     * 현재 순서 칸은 항상 비어 있어 "이 순서 어때요?" 비교에서 지금 상태를 알 수 없었다
     * (2026-09-11 사용자 제보 - 검색과 동일하게 여기도 지금 이 순간 영업 상태를 알려달라).
     * 도착 시각을 재시뮬레이션하지 않고, 검색 카드와 같은 방식(BusinessHoursEvaluator.statusAt,
     * suggest()가 받은 기준 시각 at 그대로)으로 정기휴무·영업종료만 표시한다 - "이동해서 늦게
     * 도착하면 마감"까지는 순서를 그대로 시뮬레이션해야 해서 별도 스코프(제안 칸이 이미 그 역할을 함).
     */
    private SuggestedRouteStop snapshotCurrent(ItineraryItem item, LocalDateTime at) {
        BusinessStatus status = BusinessHoursEvaluator.statusAt(hoursFields(item), at);
        boolean hard = status != BusinessStatus.OPEN;
        String reason = switch (status) {
            case CLOSED_DAY -> "REST_DAY";
            case HOURS_ENDED -> "HOURS_ENDED";
            default -> null;
        };
        return SuggestedRouteStop.builder()
                .itemId(item.getId())
                .placeName(item.getPlaceName())
                .scheduledTime(item.getScheduledTime())
                .stayMinutes(VisitTiming.stayMinutes(item))
                .contentTypeId(item.getContentTypeId())
                .visitHardToday(hard)
                .hardTodayReason(reason)
                .hardTodayLabel(hard ? hardLabel(reason) : null)
                .build();
    }

    /** 저장되는 스케줄이 30분 단위이므로 "이 순서 어때요?" 미리보기 도착 시각도 같은 규칙으로 스냅한다. */
    private static List<Planned> snapArrivals(List<Planned> planned) {
        List<LocalTime> snapped = VisitTiming.snapSequential(
                planned.stream().map(p -> p.arrival).toList());
        List<Planned> out = new ArrayList<>(planned.size());
        for (int i = 0; i < planned.size(); i++) {
            Planned p = planned.get(i);
            LocalTime a = snapped.get(i) != null ? snapped.get(i) : p.arrival;
            out.add(new Planned(p.item, a, p.travelFromPrev, p.hard, p.hardReason));
        }
        return out;
    }

    private SuggestedRouteStop toStop(Planned planned) {
        return SuggestedRouteStop.builder()
                .itemId(planned.item.getId())
                .placeName(planned.item.getPlaceName())
                .scheduledTime(planned.arrival.format(TIME_FMT))
                .visitHardToday(planned.hard)
                .hardTodayReason(planned.hardReason)
                .hardTodayLabel(planned.hard ? hardLabel(planned.hardReason) : null)
                .stayMinutes(VisitTiming.stayMinutes(planned.item))
                .travelMinutesFromPrev(planned.travelFromPrev)
                .contentTypeId(planned.item.getContentTypeId())
                .build();
    }

    static String hardLabel(String reason) {
        if (reason == null) {
            return "오늘 방문이 어려워요";
        }
        return switch (reason) {
            case "REST_DAY" -> "오늘은 정기휴무예요";
            case "CLOSING" -> "영업시간 안에 도착하기 어려워요";
            case "HOURS_ENDED" -> "지금은 영업이 끝났어요";
            case "TOO_LATE" -> "오늘 일정 끝 시각을 넘어요";
            case "TIME_OVERLAP" -> "다른 일정과 시간이 겹쳐요";
            case "NO_COORDS" -> "위치가 없어 순서만 뒤에 두었어요";
            default -> "오늘 방문이 어려워요";
        };
    }

    private static String buildMessage(boolean gps, int hardCount, boolean orderChanged,
                                       boolean timesChanged, int totalTravel) {
        if (hardCount > 0) {
            return String.format("지금 시각 기준으로 순서를 바꿔 봤어요. %d곳은 오늘 방문이 어려워요.", hardCount);
        }
        if (!orderChanged && !timesChanged) {
            return "지금 순서와 시간이 이미 괜찮아요.";
        }
        if (gps) {
            return String.format("현재 위치를 출발점으로 영업시간에 맞춰 순서를 잡아 봤어요. (이동 약 %d분)", totalTravel);
        }
        return String.format("영업시간에 맞춰 순서를 잡아 봤어요. (이동 약 %d분)", totalTravel);
    }

    private static List<Long> itemIds(List<SuggestedRouteStop> stops) {
        return stops.stream().map(SuggestedRouteStop::getItemId).toList();
    }

    private static boolean timesDiffer(List<SuggestedRouteStop> current, List<SuggestedRouteStop> suggested) {
        Map<Long, String> byId = new HashMap<>();
        for (SuggestedRouteStop stop : current) {
            byId.put(stop.getItemId(), nullToEmpty(stop.getScheduledTime()));
        }
        for (SuggestedRouteStop stop : suggested) {
            if (!Objects.equals(byId.get(stop.getItemId()), nullToEmpty(stop.getScheduledTime()))) {
                return true;
            }
        }
        return false;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record Eval(LocalTime arrival, boolean feasible, String hardReason, int travel, int score) {
    }

    private record Candidate(ItineraryItem item, Eval eval) {
    }

    private record Planned(ItineraryItem item, LocalTime arrival, int travelFromPrev, boolean hard, String hardReason) {
    }
}
