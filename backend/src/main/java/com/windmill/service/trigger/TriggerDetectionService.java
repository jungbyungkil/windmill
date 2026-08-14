package com.windmill.service.trigger;

import com.windmill.client.KakaoDirectionsClient;
import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.BusinessStatus;
import com.windmill.dto.FestivalSuggestion;
import com.windmill.dto.MapRouteRequest;
import com.windmill.dto.RegionCode;
import com.windmill.dto.TourAttractionDetail;
import com.windmill.dto.TransportMode;
import com.windmill.dto.TriggerLevel;
import com.windmill.dto.TriggerResult;
import com.windmill.service.recommendation.BusinessHoursEvaluator;
import com.windmill.service.region.RegionCodeService;
import com.windmill.service.tourapi.TourAttractionService;
import com.windmill.util.ClosingTimeGate;
import com.windmill.util.CrowdCongestionEvaluator;
import com.windmill.util.KoreaClock;
import com.windmill.util.OutdoorActivityClassifier;
import com.windmill.util.TriggerThresholds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 바람개비 실시간 변수 감지 - 기상(비)/폭염/혼잡도/영업상태/(GPS 있으면) 다음 장소까지 이동시간.
 * 비·폭염은 야외 일정에만, 휴무는 정기휴무 문구를 날짜에 맞게 판정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerDetectionService {

    private final TriggerScheduler triggerScheduler;
    private final RegionCodeService regionCodeService;
    private final TourAttractionService tourAttractionService;
    private final FestivalTriggerService festivalTriggerService;
    private final KakaoDirectionsClient kakaoDirectionsClient;

    /** 일정 전체 기준(GPS 없이) - 기존 호출부 하위 호환용 */
    public Mono<TriggerResult> detectForItinerary(Itinerary itinerary) {
        return detectForItinerary(itinerary, null, null);
    }

    /**
     * 일정 전체 기준 - 트리거 조건을 항목들에 걸쳐 OR로 판정, 축제 제안은 별도로 얹는다.
     * originLon/originLat(WGS84, 현재 위치)이 있으면 "다음 미방문 장소까지 이동시간" 트리거도 판정한다 -
     * 없으면(위치 권한 거부 등) 그 트리거만 조용히 생략하고 나머지는 그대로 동작한다.
     */
    public Mono<TriggerResult> detectForItinerary(Itinerary itinerary, Double originLon, Double originLat) {
        RegionCode region = regionCodeService.find(itinerary.getSignguFullCode())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지역코드: " + itinerary.getSignguFullCode()));
        Mono<List<FestivalSuggestion>> festivalsMono = festivalTriggerService
                .findDuringTrip(region, itinerary.getStartDate(), itinerary.getEndDate())
                .onErrorReturn(List.of());

        if (itinerary.getItems().isEmpty()) {
            return festivalsMono.map(festivals -> TriggerResult.builder()
                    .triggerCount(0).level(TriggerLevel.NORMAL).triggerDetails(List.of())
                    .affectedItemIds(List.of())
                    .weatherAffectedItemIds(List.of())
                    .businessAffectedItemIds(List.of())
                    .closedDayAffectedItemIds(List.of())
                    .hoursEndedAffectedItemIds(List.of())
                    .crowdAffectedItemIds(List.of())
                    .festivalSuggestions(festivals).build());
        }
        Mono<TriggerResult> baseMono = triggerScheduler.ensureFresh(region)
                .flatMap(condition -> Flux.fromIterable(itinerary.getItems())
                        .flatMap(item -> detect(item, condition, visitDateOf(item, itinerary))
                                .map(result -> Map.entry(item.getId(), result)))
                        .collectList()
                        .map(perItem -> {
                            TriggerResult result = aggregate(perItem);
                            attachRouteTangle(result, itinerary);
                            return result;
                        }))
                .zipWith(festivalsMono, (result, festivals) -> {
                    result.setFestivalSuggestions(festivals);
                    return result;
                });
        return baseMono.flatMap(result -> attachTravelTimeTrigger(result, itinerary, originLon, originLat));
    }

    /**
     * 다음 미방문 장소까지 이동시간이 부족해 마감 전 도착이 어려운지 판정 - 실제 도로 기준
     * `KakaoDirectionsClient`(동선 재계산에서 쓰는 것과 동일 클라이언트)로 ETA를 구해
     * 기존 `ClosingTimeGate`(마감 임박 게이트)와 동일한 기준으로 비교한다. GPS가 없거나, 다음 장소가
     * 없거나(오늘 일정 전부 시작됨), 마감 정보가 없으면 조용히 건너뛴다(단정하지 않음, 기존 철학과 동일).
     */
    private Mono<TriggerResult> attachTravelTimeTrigger(TriggerResult result, Itinerary itinerary,
                                                          Double originLon, Double originLat) {
        if (originLon == null || originLat == null) {
            return Mono.just(result);
        }
        ItineraryItem next = nextUpcomingItem(itinerary);
        if (next == null || next.getMapX() == null || next.getMapY() == null) {
            return Mono.just(result);
        }
        LocalTime close = ClosingTimeGate.parseHhMm(next.getCloseTime());
        if (close == null) {
            close = BusinessHoursEvaluator.extractCloseTimeFromText(next.getUseTimeText());
        }
        if (close == null) {
            return Mono.just(result);
        }
        LocalTime closeTime = close;
        List<MapRouteRequest.MapPoint> points = List.of(
                MapRouteRequest.MapPoint.builder().lon(originLon).lat(originLat).build(),
                MapRouteRequest.MapPoint.builder()
                        .lon(Double.parseDouble(next.getMapX()))
                        .lat(Double.parseDouble(next.getMapY()))
                        .build());
        return kakaoDirectionsClient.route(points, TransportMode.CAR)
                .map(route -> {
                    if (route.getDurationSeconds() == null) {
                        return result;
                    }
                    int travelMinutes = (int) Math.ceil(route.getDurationSeconds() / 60.0);
                    LocalTime estimatedArrival = KoreaClock.nowTime().plusMinutes(travelMinutes);
                    ClosingTimeGate.CheckResult check = ClosingTimeGate.check(closeTime, estimatedArrival);
                    if (check.blocked()) {
                        applyTravelTimeTrigger(result, next, travelMinutes, check);
                    }
                    return result;
                })
                .onErrorResume(e -> {
                    log.warn("[TriggerDetection] 이동시간 트리거 계산 실패, 생략: {}", e.getMessage());
                    return Mono.just(result);
                });
    }

    private void applyTravelTimeTrigger(TriggerResult result, ItineraryItem next, int travelMinutes,
                                         ClosingTimeGate.CheckResult check) {
        result.setTravelTimeTrigger(true);
        result.setTravelTimeAffectedItemId(next.getId());
        result.setTriggerCount(result.getTriggerCount() + 1);
        List<String> details = new ArrayList<>(result.getTriggerDetails() == null ? List.of() : result.getTriggerDetails());
        details.add(String.format("'%s'까지 약 %d분 예상돼요. %s", next.getPlaceName(), travelMinutes, check.message()));
        result.setTriggerDetails(details);
        // 이동시간 부족은 "계획 유지가 불가능"한 케이스라 곧장 변경 필요(DANGER)로 승격
        result.setLevel(TriggerLevel.DANGER);
    }

    /** 오늘(방문일) 일정 중 아직 시작 전(scheduledTime이 현재 이후)인 첫 장소 - 없으면 null */
    private ItineraryItem nextUpcomingItem(Itinerary itinerary) {
        LocalTime now = KoreaClock.nowTime();
        LocalDate today = KoreaClock.today();
        return itinerary.getItems().stream()
                .filter(item -> today.equals(visitDateOf(item, itinerary)))
                .filter(item -> {
                    LocalTime scheduled = ClosingTimeGate.parseHhMm(item.getScheduledTime());
                    return scheduled != null && scheduled.isAfter(now);
                })
                .min(Comparator.comparing(item -> ClosingTimeGate.parseHhMm(item.getScheduledTime())))
                .orElse(null);
    }

    private LocalDate visitDateOf(ItineraryItem item, Itinerary itinerary) {
        if (item.getVisitDate() != null) {
            return item.getVisitDate();
        }
        return itinerary.getStartDate() != null ? itinerary.getStartDate() : KoreaClock.today();
    }

    private void attachRouteTangle(TriggerResult result, Itinerary itinerary) {
        var tangle = com.windmill.service.itinerary.RouteTangleDetector.detect(itinerary.getItems());
        result.setRouteTangle(tangle);
        result.setRouteTangleTrigger(tangle.isTangled());
        if (tangle.isTangled()) {
            result.setTriggerCount(result.getTriggerCount() + 1);
            List<String> details = new java.util.ArrayList<>(
                    result.getTriggerDetails() == null ? List.of() : result.getTriggerDetails());
            details.add(tangle.getMessage());
            result.setTriggerDetails(details);
            if (result.getLevel() == TriggerLevel.NORMAL) {
                result.setLevel(TriggerLevel.WARNING);
            } else if (result.getLevel() == TriggerLevel.WARNING
                    && (result.isWeatherTrigger() || result.isHeatUrgent() || result.isCrowdUrgent()
                    || result.getTriggerCount() >= 2)) {
                result.setLevel(TriggerLevel.DANGER);
            }
        }
    }

    /** 단일 일정 항목 기준 트리거 판정 */
    public Mono<TriggerResult> detect(ItineraryItem item, RegionCondition condition) {
        return detect(item, condition, item.getVisitDate() != null ? item.getVisitDate() : KoreaClock.today());
    }

    public Mono<TriggerResult> detect(ItineraryItem item, RegionCondition condition, LocalDate visitDate) {
        LocalDate day = visitDate != null ? visitDate : KoreaClock.today();
        boolean rainWave = condition.getCurrentPop() != null
                && condition.getCurrentPop() >= TriggerThresholds.WEATHER_POP_THRESHOLD;

        Double heatTemp = condition.heatProxyTemp();
        boolean heatAdvisory = heatTemp != null && heatTemp >= TriggerThresholds.HEAT_ADVISORY_TMX;
        boolean heatWarning = heatTemp != null && heatTemp >= TriggerThresholds.HEAT_WARNING_TMX;
        boolean outdoor = OutdoorActivityClassifier.isOutdoor(item);

        boolean weatherTrigger = rainWave && outdoor;
        boolean heatTrigger = heatAdvisory && outdoor;
        boolean heatUrgent = heatTrigger && heatWarning;

        CrowdCongestionEvaluator.Level crowdLevel = CrowdCongestionEvaluator.evaluate(
                condition.getCrowdCategory(item.getPlaceName()),
                condition.getCrowdRelativePercent(item.getPlaceName()),
                condition.getCrowdRate(item.getPlaceName()));
        boolean crowdTrigger = crowdLevel.isTriggered();
        boolean crowdUrgent = crowdLevel.isUrgent();

        boolean closedBySnapshot = BusinessHoursEvaluator.isClosedOnRestDate(item.getRestDateText(), day);

        if (closedBySnapshot) {
            return Mono.just(buildResult(weatherTrigger, heatTrigger, heatUrgent, crowdTrigger, crowdUrgent, true, false));
        }

        // 방문일이 오늘이 아니면(미래 당일치기 사전 계획) "지금 이 순간 영업 중인지" 실시간 비교는 의미가
        // 없다 - 이 아래 라이브 체크는 실제 현재 시각을 방문일에 갖다 붙여 비교하므로, 오늘 진행 중인
        // 여행에만 적용하고 미래 방문일은 위 정기휴무 요일 판정까지만 반영한다.
        if (!day.equals(KoreaClock.today())) {
            return Mono.just(buildResult(weatherTrigger, heatTrigger, heatUrgent, crowdTrigger, crowdUrgent, false, false));
        }

        if (item.getContentId() == null || item.getContentTypeId() == null) {
            return Mono.just(buildResult(weatherTrigger, heatTrigger, heatUrgent, crowdTrigger, crowdUrgent, false, false));
        }

        LocalDateTime at = LocalDateTime.of(day, KoreaClock.nowTime());
        return tourAttractionService.getDetail(item.getContentId(), item.getContentTypeId())
                .map(TourAttractionDetail::getIntroFields)
                .map(fields -> BusinessHoursEvaluator.statusAt(fields, at))
                .map(status -> buildResult(weatherTrigger, heatTrigger, heatUrgent, crowdTrigger, crowdUrgent,
                        status == BusinessStatus.CLOSED_DAY, status == BusinessStatus.HOURS_ENDED))
                .defaultIfEmpty(buildResult(weatherTrigger, heatTrigger, heatUrgent, crowdTrigger, crowdUrgent, false, false));
    }

    private TriggerResult aggregate(List<Map.Entry<Long, TriggerResult>> perItem) {
        boolean weather = perItem.stream().anyMatch(e -> e.getValue().isWeatherTrigger());
        boolean heat = perItem.stream().anyMatch(e -> e.getValue().isHeatTrigger());
        boolean heatUrgent = perItem.stream().anyMatch(e -> e.getValue().isHeatUrgent());
        boolean crowd = perItem.stream().anyMatch(e -> e.getValue().isCrowdTrigger());
        boolean crowdUrgent = perItem.stream().anyMatch(e -> e.getValue().isCrowdUrgent());
        boolean closedDay = perItem.stream().anyMatch(e -> e.getValue().isClosedDayTrigger());
        boolean hoursEnded = perItem.stream().anyMatch(e -> e.getValue().isHoursEndedTrigger());

        List<Long> weatherIds = perItem.stream()
                .filter(e -> e.getValue().isWeatherTrigger() || e.getValue().isHeatTrigger())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        List<Long> closedDayIds = perItem.stream()
                .filter(e -> e.getValue().isClosedDayTrigger())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        List<Long> hoursEndedIds = perItem.stream()
                .filter(e -> e.getValue().isHoursEndedTrigger())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        List<Long> businessIds = new ArrayList<>();
        closedDayIds.forEach(id -> { if (!businessIds.contains(id)) businessIds.add(id); });
        hoursEndedIds.forEach(id -> { if (!businessIds.contains(id)) businessIds.add(id); });
        List<Long> crowdIds = perItem.stream()
                .filter(e -> e.getValue().isCrowdTrigger())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        List<Long> affected = new ArrayList<>();
        weatherIds.forEach(id -> { if (!affected.contains(id)) affected.add(id); });
        businessIds.forEach(id -> { if (!affected.contains(id)) affected.add(id); });
        crowdIds.forEach(id -> { if (!affected.contains(id)) affected.add(id); });

        return buildResult(weather, heat, heatUrgent, crowd, crowdUrgent, closedDay, hoursEnded,
                affected, weatherIds, businessIds, closedDayIds, hoursEndedIds, crowdIds);
    }

    private TriggerResult buildResult(boolean weather, boolean heat, boolean heatUrgent,
                                      boolean crowd, boolean crowdUrgent,
                                      boolean closedDay, boolean hoursEnded) {
        return buildResult(weather, heat, heatUrgent, crowd, crowdUrgent, closedDay, hoursEnded,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private TriggerResult buildResult(boolean weather, boolean heat, boolean heatUrgent,
                                      boolean crowd, boolean crowdUrgent,
                                      boolean closedDay, boolean hoursEnded,
                                      List<Long> affectedItemIds,
                                      List<Long> weatherAffectedItemIds,
                                      List<Long> businessAffectedItemIds,
                                      List<Long> closedDayAffectedItemIds,
                                      List<Long> hoursEndedAffectedItemIds,
                                      List<Long> crowdAffectedItemIds) {
        boolean business = closedDay || hoursEnded;
        int count = (weather ? 1 : 0) + (heat ? 1 : 0) + (crowd ? 1 : 0) + (business ? 1 : 0);
        List<String> details = new ArrayList<>();
        if (weather) {
            details.add("비 소식이 있어요. 야외 일정을 실내 코스로 바꿔보세요.");
        }
        if (heat) {
            if (heatUrgent) {
                details.add("최고기온 35℃ 이상(폭염경보 수준)이에요. 야외는 짧게, 실내 코스로 바꿔 보세요.");
            } else {
                details.add("최고기온 33℃ 이상(폭염주의보 수준)이에요. 야외는 짧게, 그늘·실내 코스로 바꿔 보세요.");
            }
        }
        if (crowd) {
            if (crowdUrgent) {
                details.add("평소보다 매우 붐벼요(긴급). 여유로운 곳으로 바꿔볼까요?");
            } else {
                details.add("혼잡도가 높아요. 여유로운 곳으로 바꿔볼까요?");
            }
        }
        if (closedDay && hoursEnded) {
            details.add("오늘(방문일) 정기휴무·영업종료인 장소가 있어요. 대체 장소를 골라보세요.");
        } else if (closedDay) {
            details.add("오늘(방문일) 정기휴무인 장소가 있어요. 대체 장소를 골라보세요.");
        } else if (hoursEnded) {
            details.add("지금 영업이 끝난 장소가 있어요. 대체 장소를 골라보세요.");
        }

        TriggerLevel level = TriggerLevel.NORMAL;
        if (count > 0) {
            // 비 / 폭염경보(35) / 혼잡 긴급 / 트리거 2개 이상 → DANGER
            // 폭염주의보(33) 단독·혼잡 주의·휴무 단독 → WARNING
            if (weather || heatUrgent || crowdUrgent || count >= 2) {
                level = TriggerLevel.DANGER;
            } else {
                level = TriggerLevel.WARNING;
            }
        }

        return TriggerResult.builder()
                .weatherTrigger(weather)
                .heatTrigger(heat)
                .heatUrgent(heatUrgent)
                .crowdTrigger(crowd)
                .crowdUrgent(crowdUrgent)
                .businessTrigger(business)
                .closedDayTrigger(closedDay)
                .hoursEndedTrigger(hoursEnded)
                .triggerCount(count)
                .level(level)
                .triggerDetails(details)
                .affectedItemIds(affectedItemIds)
                .weatherAffectedItemIds(weatherAffectedItemIds)
                .businessAffectedItemIds(businessAffectedItemIds)
                .closedDayAffectedItemIds(closedDayAffectedItemIds)
                .hoursEndedAffectedItemIds(hoursEndedAffectedItemIds)
                .crowdAffectedItemIds(crowdAffectedItemIds)
                .build();
    }
}
