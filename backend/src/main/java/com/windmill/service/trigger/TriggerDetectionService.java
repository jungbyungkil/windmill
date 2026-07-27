package com.windmill.service.trigger;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.TourAttractionDetail;
import com.windmill.dto.TriggerLevel;
import com.windmill.dto.TriggerResult;
import com.windmill.service.recommendation.BusinessHoursEvaluator;
import com.windmill.service.tourapi.TourAttractionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 바람개비 실시간 변수 감지 - 기상/혼잡도/영업상태 3조건.
 * "감지된 트리거 수"는 개별 일정 항목이 아니라 3개 조건 타입 중 몇 개가 걸렸는지를 센다 (0/1/2+ → 정상/주의/긴급).
 */
@Service
@RequiredArgsConstructor
public class TriggerDetectionService {

    /** 강수확률(%) 임계치 - 이 이상이면 기상 트리거 */
    private static final double WEATHER_POP_THRESHOLD = 60.0;
    /** 집중률(%) 임계치 - 여유율 10% 이하(=집중률 90% 이상)면 혼잡도 트리거 */
    private static final double CROWD_RATE_THRESHOLD = 90.0;

    private final RegionConditionCache regionConditionCache;
    private final TourAttractionService tourAttractionService;

    /** 일정 전체 기준 - 트리거 조건 3종을 항목들에 걸쳐 OR로 판정 */
    public Mono<TriggerResult> detectForItinerary(Itinerary itinerary) {
        if (itinerary.getItems().isEmpty()) {
            return Mono.just(TriggerResult.builder()
                    .triggerCount(0).level(TriggerLevel.NORMAL).triggerDetails(List.of()).build());
        }
        return Flux.fromIterable(itinerary.getItems())
                .flatMap(this::detect)
                .collectList()
                .map(this::aggregate);
    }

    /** 단일 일정 항목 기준 트리거 판정 */
    public Mono<TriggerResult> detect(ItineraryItem item) {
        boolean weatherTrigger = regionConditionCache.getCurrentPop() != null
                && regionConditionCache.getCurrentPop() >= WEATHER_POP_THRESHOLD;

        Double crowdRate = regionConditionCache.getCrowdRate(item.getPlaceName());
        boolean crowdTrigger = crowdRate != null && crowdRate >= CROWD_RATE_THRESHOLD;

        if (item.getContentId() == null || item.getContentTypeId() == null) {
            return Mono.just(buildResult(weatherTrigger, crowdTrigger, false));
        }
        return tourAttractionService.getDetail(item.getContentId(), item.getContentTypeId())
                .map(TourAttractionDetail::getIntroFields)
                .map(BusinessHoursEvaluator::isCurrentlyOpen)
                .map(open -> buildResult(weatherTrigger, crowdTrigger, !open))
                .defaultIfEmpty(buildResult(weatherTrigger, crowdTrigger, false));
    }

    private TriggerResult aggregate(List<TriggerResult> perItem) {
        boolean weather = perItem.stream().anyMatch(TriggerResult::isWeatherTrigger);
        boolean crowd = perItem.stream().anyMatch(TriggerResult::isCrowdTrigger);
        boolean business = perItem.stream().anyMatch(TriggerResult::isBusinessTrigger);
        return buildResult(weather, crowd, business);
    }

    private TriggerResult buildResult(boolean weather, boolean crowd, boolean business) {
        int count = (weather ? 1 : 0) + (crowd ? 1 : 0) + (business ? 1 : 0);
        List<String> details = new ArrayList<>();
        if (weather) {
            details.add("강수확률이 높아요. 실내 일정을 고려해보세요.");
        }
        if (crowd) {
            details.add("혼잡도가 높아요. 여유로운 곳으로 바꿔볼까요?");
        }
        if (business) {
            details.add("영업시간이 아니거나 휴무일 수 있어요.");
        }
        TriggerLevel level = count == 0 ? TriggerLevel.NORMAL : (count == 1 ? TriggerLevel.WARNING : TriggerLevel.DANGER);
        return TriggerResult.builder()
                .weatherTrigger(weather)
                .crowdTrigger(crowd)
                .businessTrigger(business)
                .triggerCount(count)
                .level(level)
                .triggerDetails(details)
                .build();
    }
}
