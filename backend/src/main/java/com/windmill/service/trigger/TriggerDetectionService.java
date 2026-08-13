package com.windmill.service.trigger;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.FestivalSuggestion;
import com.windmill.dto.RegionCode;
import com.windmill.dto.TourAttractionDetail;
import com.windmill.dto.TriggerLevel;
import com.windmill.dto.TriggerResult;
import com.windmill.service.recommendation.BusinessHoursEvaluator;
import com.windmill.service.region.RegionCodeService;
import com.windmill.service.tourapi.TourAttractionService;
import com.windmill.util.CrowdCongestionEvaluator;
import com.windmill.util.KoreaClock;
import com.windmill.util.OutdoorActivityClassifier;
import com.windmill.util.TriggerThresholds;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 바람개비 실시간 변수 감지 - 기상(비)/폭염/혼잡도/영업상태.
 * 비·폭염은 야외 일정에만, 휴무는 정기휴무 문구를 날짜에 맞게 판정한다.
 */
@Service
@RequiredArgsConstructor
public class TriggerDetectionService {

    private final TriggerScheduler triggerScheduler;
    private final RegionCodeService regionCodeService;
    private final TourAttractionService tourAttractionService;
    private final FestivalTriggerService festivalTriggerService;

    /** 일정 전체 기준 - 트리거 조건을 항목들에 걸쳐 OR로 판정, 축제 제안은 별도로 얹는다 */
    public Mono<TriggerResult> detectForItinerary(Itinerary itinerary) {
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
                    .crowdAffectedItemIds(List.of())
                    .festivalSuggestions(festivals).build());
        }
        return triggerScheduler.ensureFresh(region)
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
            return Mono.just(buildResult(weatherTrigger, heatTrigger, heatUrgent, crowdTrigger, crowdUrgent, true));
        }

        // 방문일이 오늘이 아니면(미래 당일치기 사전 계획) "지금 이 순간 영업 중인지" 실시간 비교는 의미가
        // 없다 - 이 아래 라이브 체크는 실제 현재 시각을 방문일에 갖다 붙여 비교하므로, 오늘 진행 중인
        // 여행에만 적용하고 미래 방문일은 위 정기휴무 요일 판정까지만 반영한다.
        if (!day.equals(KoreaClock.today())) {
            return Mono.just(buildResult(weatherTrigger, heatTrigger, heatUrgent, crowdTrigger, crowdUrgent, false));
        }

        if (item.getContentId() == null || item.getContentTypeId() == null) {
            return Mono.just(buildResult(weatherTrigger, heatTrigger, heatUrgent, crowdTrigger, crowdUrgent, false));
        }

        LocalDateTime at = LocalDateTime.of(day, KoreaClock.nowTime());
        return tourAttractionService.getDetail(item.getContentId(), item.getContentTypeId())
                .map(TourAttractionDetail::getIntroFields)
                .map(fields -> !BusinessHoursEvaluator.isOpenAt(fields, at))
                .map(closed -> buildResult(weatherTrigger, heatTrigger, heatUrgent, crowdTrigger, crowdUrgent, closed))
                .defaultIfEmpty(buildResult(weatherTrigger, heatTrigger, heatUrgent, crowdTrigger, crowdUrgent, false));
    }

    private TriggerResult aggregate(List<Map.Entry<Long, TriggerResult>> perItem) {
        boolean weather = perItem.stream().anyMatch(e -> e.getValue().isWeatherTrigger());
        boolean heat = perItem.stream().anyMatch(e -> e.getValue().isHeatTrigger());
        boolean heatUrgent = perItem.stream().anyMatch(e -> e.getValue().isHeatUrgent());
        boolean crowd = perItem.stream().anyMatch(e -> e.getValue().isCrowdTrigger());
        boolean crowdUrgent = perItem.stream().anyMatch(e -> e.getValue().isCrowdUrgent());
        boolean business = perItem.stream().anyMatch(e -> e.getValue().isBusinessTrigger());

        List<Long> weatherIds = perItem.stream()
                .filter(e -> e.getValue().isWeatherTrigger() || e.getValue().isHeatTrigger())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        List<Long> businessIds = perItem.stream()
                .filter(e -> e.getValue().isBusinessTrigger())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        List<Long> crowdIds = perItem.stream()
                .filter(e -> e.getValue().isCrowdTrigger())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        List<Long> affected = new ArrayList<>();
        weatherIds.forEach(id -> { if (!affected.contains(id)) affected.add(id); });
        businessIds.forEach(id -> { if (!affected.contains(id)) affected.add(id); });
        crowdIds.forEach(id -> { if (!affected.contains(id)) affected.add(id); });

        return buildResult(weather, heat, heatUrgent, crowd, crowdUrgent, business,
                affected, weatherIds, businessIds, crowdIds);
    }

    private TriggerResult buildResult(boolean weather, boolean heat, boolean heatUrgent,
                                      boolean crowd, boolean crowdUrgent, boolean business) {
        return buildResult(weather, heat, heatUrgent, crowd, crowdUrgent, business,
                List.of(), List.of(), List.of(), List.of());
    }

    private TriggerResult buildResult(boolean weather, boolean heat, boolean heatUrgent,
                                      boolean crowd, boolean crowdUrgent, boolean business,
                                      List<Long> affectedItemIds,
                                      List<Long> weatherAffectedItemIds,
                                      List<Long> businessAffectedItemIds,
                                      List<Long> crowdAffectedItemIds) {
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
        if (business) {
            details.add("오늘(방문일) 정기휴무·영업종료인 장소가 있어요. 대체 장소를 골라보세요.");
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
                .triggerCount(count)
                .level(level)
                .triggerDetails(details)
                .affectedItemIds(affectedItemIds)
                .weatherAffectedItemIds(weatherAffectedItemIds)
                .businessAffectedItemIds(businessAffectedItemIds)
                .crowdAffectedItemIds(crowdAffectedItemIds)
                .build();
    }
}
