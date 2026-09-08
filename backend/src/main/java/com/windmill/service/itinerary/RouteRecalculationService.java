package com.windmill.service.itinerary;

import com.windmill.client.KakaoDirectionsClient;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.MapRouteRequest;
import com.windmill.service.recommendation.BusinessHoursEvaluator;
import com.windmill.util.ClosingTimeGate;
import com.windmill.util.GeoUtils;
import com.windmill.util.KoreaClock;
import com.windmill.util.VisitOrderOptimizer;
import com.windmill.util.VisitTiming;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
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

    private static final LocalTime DAY_START = VisitTiming.DAY_START;
    private static final LocalTime LATEST_START = VisitTiming.LATEST_START;
    // 식사 시간대는 "정확히 이 시각"이 아니라 창(window)으로 다룬다 - 자연스러운 도착 시각이 이미
    // 창 안이면 건드리지 않고, 창보다 이르면 창 시작으로만 최소한 당긴다(옛날엔 무조건 12:00/18:00
    // 정각으로 점프시켜 미래 일정(하루 전체 09:00부터 사용 가능)에서도 식사 앞뒤로 몇 시간씩 빈
    // 채로 건너뛰고 나머지 일정이 저녁으로 몰리는 문제가 있었음).
    private static final LocalTime LUNCH_WINDOW_START = VisitTiming.LUNCH_WINDOW_START;
    private static final LocalTime LUNCH_WINDOW_END = VisitTiming.LUNCH_WINDOW_END;
    private static final LocalTime DINNER_WINDOW_START = VisitTiming.DINNER_WINDOW_START;
    private static final LocalTime DINNER_WINDOW_END = VisitTiming.DINNER_WINDOW_END;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final KakaoDirectionsClient kakaoDirectionsClient;

    public Result recalculate(List<ItineraryItem> targets, Double originLon, Double originLat) {
        return recalculate(targets, originLon, originLat, null);
    }

    /**
     * @param overrideStartTime 첫 장소 도착 시각을 사용자가 직접 지정 - 주어지면 오늘/미래 자동
     *                          판정(resolveDayStart)을 건너뛰고 이 시각부터 시작해, 나머지는
     *                          그 뒤로 실제 체류·이동시간만큼 자연스럽게 이어 붙는다.
     */
    public Result recalculate(List<ItineraryItem> targets, Double originLon, Double originLat,
                               LocalTime overrideStartTime) {
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
            assignSchedule(order, null, null, null, overrideStartTime);
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

        // 바람개비 "지금 Xkm → 재배치 시 약 Ykm"와 같은 직선거리 최단 순서를 쓴다.
        // 카카오 분 단위 TSP는 도로 시간은 반영하지만, 식사 분리(declump)와 맞물리면
        // 감지기가 약속한 단축이 실제로 적용되지 않아 재계산 버튼이 먹통처럼 보였다.
        List<ItineraryItem> tspOrder = chooseShortestVisitOrder(withCoords, originLon, originLat);

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
        // 맛집이 연달아 있으면 점심·저녁 사이에 관광을 끼워 하루가 저녁으로 몰리지 않게 한다.
        // 다만 그 때문에 동선이 다시 꼬이면(감지기 기준) 최단 순서를 유지한다.
        open = declumpIfItDoesNotRetangle(open);

        Map<Long, Integer> idToIdx = new HashMap<>();
        for (int i = 0; i < withCoords.size(); i++) {
            idToIdx.put(withCoords.get(i).getId(), i);
        }

        // TSP는 거리만 보고 순서를 정하므로, 마감이 이른 장소가 뒤쪽으로 밀려 마감시간을 넘긴 채로
        // 표시될 수 있다(예: 방금 마감시간 기준으로 알맞은 자리에 끼워 넣은 장소를, 뒤이어 동선
        // 재계산을 돌리면 순수 거리 기준으로 다시 흩어놓아 마감을 넘겨버림). 마감을 넘기는 장소가
        // 있으면 그 장소만 더 이른(마감을 안 넘기는 가장 늦은) 자리로 옮긴다 - 정기휴무(closed)·
        // 좌표없음(without) 구간은 대상에서 제외(declump와 동일 스코프).
        open = repairClosingTimeConflicts(open, matrix.minutes(), idToIdx, overrideStartTime);

        List<ItineraryItem> finalOrder = new ArrayList<>(open);
        finalOrder.addAll(closed);
        finalOrder.addAll(without);

        int totalTravel = assignSchedule(finalOrder, matrix.minutes(), idToIdx, fromOrigin, overrideStartTime);
        String message = useOrigin
                ? String.format("현재 위치를 출발점으로 최단 순서를 잡고 시간표를 다시 잡았어요. (이동 약 %d분)", totalTravel)
                : String.format("꼬인 동선을 최단 순서로 바꾸고, 체류·이동을 반영해 시간표를 다시 잡았어요. (이동 약 %d분)", totalTravel);
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
        return assignSchedule(ordered, minutes, idToIdx, fromOrigin, null);
    }

    // package-private: 테스트에서 직접 호출
    int assignSchedule(List<ItineraryItem> ordered,
                               int[][] minutes,
                               Map<Long, Integer> idToIdx,
                               int[] fromOrigin,
                               LocalTime overrideStartTime) {
        LocalTime[] arrivals = simulateArrivals(ordered, minutes, idToIdx, overrideStartTime);
        // 저장되는 스케줄 값은 30분 단위 올림 스냅(중복·역전은 +30분). 사용자가 지정한 첫 도착
        // 시각(overrideStartTime)은 정확히 유지하고 이후 장소만 스냅 결과를 쓴다.
        List<LocalTime> snapped = VisitTiming.snapSequential(Arrays.asList(arrivals));
        if (overrideStartTime != null && !snapped.isEmpty()) {
            snapped.set(0, arrivals[0]);
        }
        int totalTravel = 0;
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setScheduledTime(snapped.get(i).format(TIME_FMT));
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
    private LocalTime[] simulateArrivals(List<ItineraryItem> ordered, int[][] minutes, Map<Long, Integer> idToIdx,
                                          LocalTime overrideStartTime) {
        LocalTime[] arrivals = new LocalTime[ordered.size()];
        LocalTime cursor = overrideStartTime != null ? overrideStartTime : resolveDayStart(ordered);
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

            int stay = VisitTiming.stayMinutes(item);
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
        return GeoUtils.DEFAULT_TRAVEL_MINUTES;
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
        return repairClosingTimeConflicts(ordered, minutes, idToIdx, null);
    }

    // package-private: 테스트에서 직접 호출
    List<ItineraryItem> repairClosingTimeConflicts(List<ItineraryItem> ordered, int[][] minutes,
                                                              Map<Long, Integer> idToIdx,
                                                              LocalTime overrideStartTime) {
        List<ItineraryItem> result = new ArrayList<>(ordered);
        for (int pass = 0; pass < result.size(); pass++) {
            LocalTime[] arrivals = simulateArrivals(result, minutes, idToIdx, overrideStartTime);
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
                LocalTime[] candArrivals = simulateArrivals(candidate, minutes, idToIdx, overrideStartTime);
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

    /**
     * 동선 재계산용 마감. 파싱된 CLOSE/usetime만 쓰고, 편집 게이트의 17/18시 기본값은 넣지 않는다.
     * 기본 마감을 여기 넣으면 마감 미상 장소까지 저녁 슬롯이 전부 앞으로 밀린다.
     */
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
     * 바람개비 동선 꼬임 감지기와 동일한 직선거리 최단 방문 순서.
     */
    static List<ItineraryItem> chooseShortestVisitOrder(List<ItineraryItem> withCoords,
                                                        Double originLon, Double originLat) {
        if (originLon != null && originLat != null) {
            return VisitOrderOptimizer.optimizeFromOrigin(
                    withCoords,
                    String.valueOf(originLon),
                    String.valueOf(originLat),
                    ItineraryItem::getMapX,
                    ItineraryItem::getMapY);
        }
        return VisitOrderOptimizer.optimize(withCoords, ItineraryItem::getMapX, ItineraryItem::getMapY);
    }

    /**
     * 식사 분리가 최단 동선을 다시 꼬이게 하면 원래 순서를 유지한다.
     */
    static List<ItineraryItem> declumpIfItDoesNotRetangle(List<ItineraryItem> open) {
        List<ItineraryItem> declumped = declumpAdjacentMeals(open);
        if (declumped.equals(open)) {
            return declumped;
        }
        boolean wasShort = !RouteTangleDetector.detect(copyWithDisplayOrder(open)).isTangled();
        boolean nowTangled = RouteTangleDetector.detect(copyWithDisplayOrder(declumped)).isTangled();
        if (wasShort && nowTangled) {
            return open;
        }
        return declumped;
    }

    private static List<ItineraryItem> copyWithDisplayOrder(List<ItineraryItem> items) {
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setDisplayOrder(i);
        }
        return items;
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
        return VisitTiming.isMeal(item);
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
