package com.windmill.service.itinerary;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.BatchPlaceCheckRequest;
import com.windmill.dto.PlaceCheckResult;
import com.windmill.dto.RegionCode;
import com.windmill.dto.TriggerLevel;
import com.windmill.dto.TriggerResult;
import com.windmill.repository.ItineraryRepository;
import com.windmill.service.region.RegionCodeService;
import com.windmill.service.trigger.TriggerDetectionService;
import com.windmill.service.trigger.TriggerScheduler;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 여행 바구니 바텀시트 - 일괄 공공데이터 검증(2026-09-15 핸드오프 브리프 4.1, 9.5, 10.3, 10.4).
 * 기존 단건 트리거 판정({@link TriggerDetectionService#detect}, 영업시간·혼잡도·날씨를 한 번에
 * 본다)을 그대로 재사용한다 - 새로 계산 로직을 만들지 않는다. 아직 일정에 없는 바구니 항목이라
 * 저장하지 않은 임시(transient) ItineraryItem으로 감싸 넘긴다.
 *
 * <p>휴무(요일)·실시간 비/폭염/혼잡만 본다 - "배정될 슬롯 시각" 기준 마감 임박 예측은 하지 않는다
 * (바구니 단계에선 슬롯이 아직 없음, 하루 끝 자동 배정). 실제 마감 충돌은 확정 시점에
 * {@link ItineraryService#addItemsBatch}가 기존 삽입 로직으로 권위 있게 걸러 롤백한다 - 이 검증은
 * 확정 전 "미리 보기"일 뿐이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaceBatchCheckService {

    /** Flux.flatMap 동시성 상한 - 전량 동시 발사 금지(결정 10.3), 배치 단위로 나눠 처리 */
    private static final int CONCURRENCY = 5;

    private final ItineraryRepository itineraryRepository;
    private final RegionCodeService regionCodeService;
    private final TriggerScheduler triggerScheduler;
    private final TriggerDetectionService triggerDetectionService;

    public Mono<List<PlaceCheckResult>> checkBatch(Long itineraryId, BatchPlaceCheckRequest request) {
        Itinerary itinerary = itineraryRepository.findById(itineraryId)
                .orElseThrow(() -> new EntityNotFoundException("일정을 찾을 수 없습니다: " + itineraryId));
        RegionCode region = regionCodeService.find(itinerary.getSignguFullCode())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지역코드: " + itinerary.getSignguFullCode()));

        return triggerScheduler.ensureFresh(region)
                .flatMapMany(condition -> Flux.fromIterable(request.getItems())
                        .flatMap(item -> triggerDetectionService
                                .detect(toTransientItem(item), condition, itinerary.getStartDate())
                                .map(result -> toCheckResult(item.getContentId(), result))
                                .onErrorResume(e -> {
                                    log.warn("[BatchCheck] itineraryId={} contentId={} 검증 실패, 정상으로 처리: {}",
                                            itineraryId, item.getContentId(), e.toString());
                                    return Mono.just(PlaceCheckResult.builder()
                                            .contentId(item.getContentId())
                                            .urgent(false).warning(false).reasons(List.of())
                                            .build());
                                }), CONCURRENCY))
                .collectList();
    }

    private static ItineraryItem toTransientItem(BatchPlaceCheckRequest.Item req) {
        return ItineraryItem.builder()
                .contentId(req.getContentId())
                .contentTypeId(req.getContentTypeId())
                .placeName(req.getPlaceName())
                .category(req.getCategory())
                .cat3(req.getCat3())
                .tags(req.getTags() == null ? List.of() : req.getTags())
                .restDateText(req.getRestDateText())
                .closeTime(req.getCloseTime())
                .useTimeText(req.getUseTimeText())
                .build();
    }

    private static PlaceCheckResult toCheckResult(String contentId, TriggerResult result) {
        List<String> reasons = new ArrayList<>();
        // 우선순위: 휴무 > 혼잡 > 폭염 > 비(핸드오프 브리프 8절 결정 #8과 동일한 우선순위 감각)
        if (result.isClosedDayTrigger()) reasons.add("휴무");
        if (result.isCrowdTrigger()) reasons.add("혼잡");
        if (result.isHeatTrigger()) reasons.add("폭염");
        if (result.isWeatherTrigger()) reasons.add("우천");
        if (result.isHoursEndedTrigger()) reasons.add("마감임박");
        if (reasons.size() > 3) {
            reasons = reasons.subList(0, 3);
        }
        String detail = result.getTriggerDetails() != null && !result.getTriggerDetails().isEmpty()
                ? result.getTriggerDetails().get(0) : null;
        return PlaceCheckResult.builder()
                .contentId(contentId)
                .urgent(result.getLevel() == TriggerLevel.DANGER)
                .warning(result.getLevel() == TriggerLevel.WARNING || result.getLevel() == TriggerLevel.DANGER)
                .reasons(reasons)
                .detail(detail)
                .build();
    }

}
