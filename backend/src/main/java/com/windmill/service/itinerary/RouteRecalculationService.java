package com.windmill.service.itinerary;

import com.windmill.client.KakaoDirectionsClient;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.MapRouteRequest;
import com.windmill.service.recommendation.BusinessHoursEvaluator;
import com.windmill.util.VisitOrderOptimizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 동선 재계산: 카카오 이동시간 매트릭스 → TSP → 체류·이동·휴무를 반영한 시간표.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteRecalculationService {

    private static final int ATTRACTION_STAY = 75;
    private static final int MEAL_STAY = 60;
    private static final LocalTime DAY_START = LocalTime.of(9, 0);
    private static final LocalTime LATEST_START = LocalTime.of(20, 0);
    private static final LocalTime LUNCH_ANCHOR = LocalTime.of(12, 0);
    private static final LocalTime DINNER_ANCHOR = LocalTime.of(18, 0);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final KakaoDirectionsClient kakaoDirectionsClient;

    public Result recalculate(List<ItineraryItem> targets, Double originLon, Double originLat) {
        if (targets == null || targets.size() < 2) {
            return new Result(targets == null ? List.of() : targets, null, null, false);
        }

        List<ItineraryItem> withCoords = new ArrayList<>();
        List<ItineraryItem> without = new ArrayList<>();
        for (ItineraryItem item : targets) {
            if (hasCoords(item)) {
                withCoords.add(item);
            } else {
                without.add(item);
            }
        }
        if (withCoords.size() <= 1) {
            List<ItineraryItem> order = new ArrayList<>(withCoords);
            order.addAll(without);
            assignSchedule(order, null, null, null);
            return new Result(order, "좌표가 있는 장소가 적어 순서만 유지하고 시간표를 다시 잡았어요.", null, false);
        }

        List<MapRouteRequest.MapPoint> points = withCoords.stream()
                .map(this::toPoint)
                .toList();

        KakaoDirectionsClient.TravelTimeMatrix matrix =
                kakaoDirectionsClient.buildTravelTimeMatrix(points);

        int[] fromOrigin = null;
        boolean useOrigin = originLon != null && originLat != null;
        if (useOrigin) {
            MapRouteRequest.MapPoint origin = MapRouteRequest.MapPoint.builder()
                    .lon(originLon)
                    .lat(originLat)
                    .name("현재 위치")
                    .build();
            fromOrigin = kakaoDirectionsClient.minutesFromOrigin(origin, points);
        }

        List<ItineraryItem> tspOrder = VisitOrderOptimizer.optimizeWithTravelMinutes(
                withCoords, matrix.minutes(), fromOrigin);

        // 정기휴무인 곳은 뒤로 — 영업 가능한 슬롯을 먼저 채움
        LocalDate visitDate = resolveVisitDate(tspOrder);
        List<ItineraryItem> open = new ArrayList<>();
        List<ItineraryItem> closed = new ArrayList<>();
        for (ItineraryItem item : tspOrder) {
            if (visitDate != null && BusinessHoursEvaluator.isClosedOnRestDate(item.getRestDateText(), visitDate)) {
                closed.add(item);
            } else {
                open.add(item);
            }
        }
        List<ItineraryItem> finalOrder = new ArrayList<>(open);
        finalOrder.addAll(closed);
        finalOrder.addAll(without);

        Map<Long, Integer> idToIdx = new HashMap<>();
        for (int i = 0; i < withCoords.size(); i++) {
            idToIdx.put(withCoords.get(i).getId(), i);
        }

        int totalTravel = assignSchedule(finalOrder, matrix.minutes(), idToIdx, fromOrigin);
        String source = matrix.roadBased() ? "카카오 도로 이동시간" : "직선거리 추정";
        String message = useOrigin
                ? String.format("현재 위치 기준으로 %s TSP 재계산 · 이동 약 %d분 · 시간표를 다시 잡았어요.", source, totalTravel)
                : String.format("%s TSP로 순서를 잡고, 체류·이동을 반영해 시간표를 다시 잡았어요. (이동 약 %d분)", source, totalTravel);
        log.info("[RouteRecalc] n={} roadBased={} travelMin={} closedToday={}",
                withCoords.size(), matrix.roadBased(), totalTravel, closed.size());
        return new Result(finalOrder, message, totalTravel, matrix.roadBased());
    }

    /**
     * @return 총 이동 분(체류 제외)
     */
    private int assignSchedule(List<ItineraryItem> ordered,
                               int[][] minutes,
                               Map<Long, Integer> idToIdx,
                               int[] fromOrigin) {
        LocalTime cursor = resolveDayStart(ordered);
        int totalTravel = 0;
        boolean lunchUsed = false;
        boolean dinnerUsed = false;

        for (int i = 0; i < ordered.size(); i++) {
            ItineraryItem item = ordered.get(i);
            boolean meal = isMeal(item);

            if (meal && !lunchUsed && !cursor.isAfter(LocalTime.of(14, 0))) {
                cursor = maxTime(cursor, LUNCH_ANCHOR);
                lunchUsed = true;
            } else if (meal && !dinnerUsed && !cursor.isAfter(LocalTime.of(19, 30))) {
                cursor = maxTime(cursor, DINNER_ANCHOR);
                dinnerUsed = true;
            }

            if (cursor.isAfter(LATEST_START)) {
                cursor = LATEST_START;
            }
            item.setScheduledTime(cursor.format(TIME_FMT));

            int stay = meal ? MEAL_STAY : ATTRACTION_STAY;
            int travel = 0;
            if (i + 1 < ordered.size() && minutes != null && idToIdx != null) {
                ItineraryItem next = ordered.get(i + 1);
                Integer a = idToIdx.get(item.getId());
                Integer b = idToIdx.get(next.getId());
                if (a != null && b != null) {
                    travel = minutes[a][b];
                } else {
                    travel = 20;
                }
            } else if (i + 1 < ordered.size()) {
                travel = 20;
            }
            totalTravel += travel;
            cursor = cursor.plusMinutes(stay + travel);
        }

        // origin→첫 장소 이동은 총 이동에만 반영 (일정 시작 시각은 도착 기준)
        if (fromOrigin != null && !ordered.isEmpty() && idToIdx != null) {
            Integer firstIdx = idToIdx.get(ordered.get(0).getId());
            if (firstIdx != null && firstIdx < fromOrigin.length) {
                totalTravel += fromOrigin[firstIdx];
            }
        }
        return totalTravel;
    }

    private LocalTime resolveDayStart(List<ItineraryItem> ordered) {
        LocalTime cursor = DAY_START;
        LocalDate visit = resolveVisitDate(ordered);
        if (visit != null && visit.equals(LocalDate.now())) {
            LocalTime soon = LocalTime.now().plusMinutes(30).withSecond(0).withNano(0);
            int m = soon.getMinute();
            if (m == 0) {
                cursor = soon;
            } else if (m <= 30) {
                cursor = soon.withMinute(30);
            } else {
                cursor = soon.plusHours(1).withMinute(0);
            }
            if (cursor.isBefore(DAY_START)) {
                cursor = DAY_START;
            }
        }
        return cursor;
    }

    private static LocalDate resolveVisitDate(List<ItineraryItem> ordered) {
        if (ordered == null || ordered.isEmpty()) {
            return null;
        }
        ItineraryItem first = ordered.get(0);
        if (first.getVisitDate() != null) {
            return first.getVisitDate();
        }
        if (first.getItinerary() != null) {
            return first.getItinerary().getStartDate();
        }
        return null;
    }

    private static boolean isMeal(ItineraryItem item) {
        if (item.getTags() != null) {
            for (String t : item.getTags()) {
                if ("#맛집".equals(t)) {
                    return true;
                }
            }
        }
        String cat = item.getCategory();
        return cat != null && (cat.contains("맛집") || cat.contains("식사") || cat.contains("음식"));
    }

    private static LocalTime maxTime(LocalTime a, LocalTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static boolean hasCoords(ItineraryItem item) {
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

    public record Result(List<ItineraryItem> ordered, String message, Integer totalTravelMinutes, boolean roadBased) {
    }
}
