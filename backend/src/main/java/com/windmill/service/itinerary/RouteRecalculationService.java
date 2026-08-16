package com.windmill.service.itinerary;

import com.windmill.client.KakaoDirectionsClient;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.MapRouteRequest;
import com.windmill.service.recommendation.BusinessHoursEvaluator;
import com.windmill.util.ClosingTimeGate;
import com.windmill.util.KoreaClock;
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
    // 식사 시간대는 "정확히 이 시각"이 아니라 창(window)으로 다룬다 - 자연스러운 도착 시각이 이미
    // 창 안이면 건드리지 않고, 창보다 이르면 창 시작으로만 최소한 당긴다(옛날엔 무조건 12:00/18:00
    // 정각으로 점프시켜 미래 일정(하루 전체 09:00부터 사용 가능)에서도 식사 앞뒤로 몇 시간씩 빈
    // 채로 건너뛰고 나머지 일정이 저녁으로 몰리는 문제가 있었음).
    private static final LocalTime LUNCH_WINDOW_START = LocalTime.of(11, 0);
    private static final LocalTime LUNCH_WINDOW_END = LocalTime.of(14, 0);
    private static final LocalTime DINNER_WINDOW_START = LocalTime.of(17, 0);
    private static final LocalTime DINNER_WINDOW_END = LocalTime.of(19, 30);
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
        // TSP는 순수 이동거리만 보므로 맛집 태그 장소끼리 가까이 있으면 연달아 붙어버릴 수 있다
        // (예: 맛집→맛집→관광→관광). 그대로 두면 점심/저녁 앵커가 서로 붙어 배정되고 관광 일정이
        // 저녁 시간대로 몰린다 - 두 식사 사이에 그 뒤에 오는 가장 가까운(순서상) 비-식사 장소를
        // 끌어와 끼워 넣어 자연스럽게 하루에 흩어지도록 한다. 영업 가능한(open) 구간에만 적용 -
        // 정기휴무(closed)·좌표없음(without) 뒤로 미는 순서는 그대로 유지.
        open = declumpAdjacentMeals(open);

        Map<Long, Integer> idToIdx = new HashMap<>();
        for (int i = 0; i < withCoords.size(); i++) {
            idToIdx.put(withCoords.get(i).getId(), i);
        }

        // TSP는 거리만 보고 순서를 정하므로, 마감이 이른 장소가 뒤쪽으로 밀려 마감시간을 넘긴 채로
        // 표시될 수 있다(예: 방금 마감시간 기준으로 알맞은 자리에 끼워 넣은 장소를, 뒤이어 동선
        // 재계산을 돌리면 순수 거리 기준으로 다시 흩어놓아 마감을 넘겨버림). 마감을 넘기는 장소가
        // 있으면 그 장소만 더 이른(마감을 안 넘기는 가장 늦은) 자리로 옮긴다 - 정기휴무(closed)·
        // 좌표없음(without) 구간은 대상에서 제외(declump와 동일 스코프).
        open = repairClosingTimeConflicts(open, matrix.minutes(), idToIdx);

        List<ItineraryItem> finalOrder = new ArrayList<>(open);
        finalOrder.addAll(closed);
        finalOrder.addAll(without);

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
    // package-private: 테스트에서 직접 호출
    int assignSchedule(List<ItineraryItem> ordered,
                               int[][] minutes,
                               Map<Long, Integer> idToIdx,
                               int[] fromOrigin) {
        LocalTime[] arrivals = simulateArrivals(ordered, minutes, idToIdx);
        int totalTravel = 0;
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setScheduledTime(arrivals[i].format(TIME_FMT));
            totalTravel += travelBetween(ordered, i, minutes, idToIdx);
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

    /**
     * 실제로 항목에 시각을 쓰지 않고(부작용 없이) 순서대로 도착 시각만 시뮬레이션한다 - 마감시간
     * 위반 재배치(repairClosingTimeConflicts)가 후보 순서를 여러 번 가볍게 미리 계산해봐야 해서
     * assignSchedule과 로직을 공유하되 분리했다.
     */
    private LocalTime[] simulateArrivals(List<ItineraryItem> ordered, int[][] minutes, Map<Long, Integer> idToIdx) {
        LocalTime[] arrivals = new LocalTime[ordered.size()];
        LocalTime cursor = resolveDayStart(ordered);
        boolean lunchUsed = false;
        boolean dinnerUsed = false;

        for (int i = 0; i < ordered.size(); i++) {
            ItineraryItem item = ordered.get(i);
            boolean meal = isMeal(item);

            if (meal && !lunchUsed && !cursor.isAfter(LUNCH_WINDOW_END)) {
                if (cursor.isBefore(LUNCH_WINDOW_START)) {
                    cursor = LUNCH_WINDOW_START;
                }
                lunchUsed = true;
            } else if (meal && !dinnerUsed && !cursor.isAfter(DINNER_WINDOW_END)) {
                if (cursor.isBefore(DINNER_WINDOW_START)) {
                    cursor = DINNER_WINDOW_START;
                }
                dinnerUsed = true;
            }

            if (cursor.isAfter(LATEST_START)) {
                cursor = LATEST_START;
            }
            arrivals[i] = cursor;

            int stay = meal ? MEAL_STAY : ATTRACTION_STAY;
            cursor = cursor.plusMinutes(stay + travelBetween(ordered, i, minutes, idToIdx));
        }
        return arrivals;
    }

    private static int travelBetween(List<ItineraryItem> ordered, int i, int[][] minutes, Map<Long, Integer> idToIdx) {
        if (i + 1 >= ordered.size()) {
            return 0;
        }
        if (minutes != null && idToIdx != null) {
            Integer a = idToIdx.get(ordered.get(i).getId());
            Integer b = idToIdx.get(ordered.get(i + 1).getId());
            if (a != null && b != null) {
                return minutes[a][b];
            }
        }
        return 20;
    }

    /**
     * 마감시간을 넘기는 장소가 있으면, 그 장소를 마감을 넘기지 않는 자리 중 가장 늦은 위치로
     * 옮긴다(뒤쪽 일정을 최대한 안 건드리기 위함). 옮겨서 다른 장소가 새로 마감을 넘기게 되면
     * 그 자리는 채택하지 않는다(전체 위반 건수가 실제로 줄어드는 자리만 채택) - 최대 목록 크기만큼
     * 반복하며, 더 옮길 자리가 없으면 남은 위반은 그대로 둔다(assignSchedule이 LATEST_START로
     * 캡해서 화면엔 마지막 가능 시각으로 표시됨).
     */
    // package-private: 테스트에서 직접 호출
    List<ItineraryItem> repairClosingTimeConflicts(List<ItineraryItem> ordered, int[][] minutes,
                                                              Map<Long, Integer> idToIdx) {
        List<ItineraryItem> result = new ArrayList<>(ordered);
        for (int pass = 0; pass < result.size(); pass++) {
            LocalTime[] arrivals = simulateArrivals(result, minutes, idToIdx);
            List<Integer> violations = closingViolations(result, arrivals);
            if (violations.isEmpty()) {
                break;
            }
            int violationIdx = violations.get(0);
            ItineraryItem moving = result.get(violationIdx);
            LocalTime close = closeTimeOf(moving);

            int bestPos = -1;
            for (int p = violationIdx - 1; p >= 0; p--) {
                List<ItineraryItem> candidate = new ArrayList<>(result);
                candidate.remove(violationIdx);
                candidate.add(p, moving);
                LocalTime[] candArrivals = simulateArrivals(candidate, minutes, idToIdx);
                if (ClosingTimeGate.check(close, candArrivals[p]).allowed()
                        && closingViolations(candidate, candArrivals).size() < violations.size()) {
                    bestPos = p;
                    break;
                }
            }
            if (bestPos < 0) {
                break; // 더 옮길 자리 없음 - 포기
            }
            result.remove(violationIdx);
            result.add(bestPos, moving);
        }
        return result;
    }

    private static List<Integer> closingViolations(List<ItineraryItem> ordered, LocalTime[] arrivals) {
        List<Integer> violations = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            LocalTime close = closeTimeOf(ordered.get(i));
            if (close != null && !ClosingTimeGate.check(close, arrivals[i]).allowed()) {
                violations.add(i);
            }
        }
        return violations;
    }

    private static LocalTime closeTimeOf(ItineraryItem item) {
        LocalTime close = ClosingTimeGate.parseHhMm(item.getCloseTime());
        if (close == null) {
            close = BusinessHoursEvaluator.extractCloseTimeFromText(item.getUseTimeText());
        }
        return close;
    }

    private LocalTime resolveDayStart(List<ItineraryItem> ordered) {
        LocalTime cursor = DAY_START;
        LocalDate visit = resolveVisitDate(ordered);
        if (visit != null && visit.equals(KoreaClock.today())) {
            LocalTime soon = KoreaClock.nowTime().plusMinutes(30).withSecond(0).withNano(0);
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

    /**
     * 인접한 두 식사(맛집) 장소 사이에, 그 뒤에 오는 가장 가까운 순서의 비-식사 장소를 하나 끌어와
     * 끼워 넣는다. 이동시간 매트릭스는 id 쌍으로 조회하므로(순서와 무관) 순서만 바뀌어도 시간표
     * 계산은 실제 이동시간을 그대로 반영한다 - 별도 거리 재계산 불필요.
     */
    // package-private: 테스트에서 직접 호출
    static List<ItineraryItem> declumpAdjacentMeals(List<ItineraryItem> ordered) {
        List<ItineraryItem> result = new ArrayList<>(ordered);
        for (int i = 0; i < result.size() - 1; i++) {
            if (isMeal(result.get(i)) && isMeal(result.get(i + 1))) {
                int pullIdx = -1;
                for (int j = i + 2; j < result.size(); j++) {
                    if (!isMeal(result.get(j))) {
                        pullIdx = j;
                        break;
                    }
                }
                if (pullIdx != -1) {
                    ItineraryItem pulled = result.remove(pullIdx);
                    result.add(i + 1, pulled);
                }
            }
        }
        return result;
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
