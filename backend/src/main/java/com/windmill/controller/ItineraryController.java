package com.windmill.controller;

import com.windmill.domain.Itinerary;
import com.windmill.dto.AddItineraryItemRequest;
import com.windmill.dto.ItineraryResponse;
import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RecommendationRequest;
import com.windmill.dto.TriggerResult;
import com.windmill.dto.UpdateItineraryItemRequest;
import com.windmill.service.itinerary.ItineraryService;
import com.windmill.service.recommendation.InitialPlanService;
import com.windmill.service.recommendation.RecommendationPipeline;
import com.windmill.service.trigger.TriggerDetectionService;
import com.windmill.service.trip.TripRecordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/itineraries")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ItineraryController {

    private final ItineraryService itineraryService;
    private final TriggerDetectionService triggerDetectionService;
    private final RecommendationPipeline recommendationPipeline;
    private final InitialPlanService initialPlanService;
    private final TripRecordService tripRecordService;

    @PostMapping
    public Mono<ResponseEntity<ItineraryResponse>> create(
            @RequestHeader("X-Session-Id") String sessionId,
            @RequestParam(required = false) String destination) {
        return Mono.fromCallable(() -> itineraryService.create(sessionId, destination))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ItineraryResponse::from)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<ItineraryResponse>> get(@PathVariable Long id) {
        return Mono.fromCallable(() -> itineraryService.get(id))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ItineraryResponse::from)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{id}/items")
    public Mono<ResponseEntity<ItineraryResponse>> addItem(@PathVariable Long id,
                                                            @Valid @RequestBody AddItineraryItemRequest request) {
        return Mono.fromCallable(() -> itineraryService.addItem(id, request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ItineraryResponse::from)
                .map(ResponseEntity::ok);
    }

    @PatchMapping("/{id}/items/{itemId}")
    public Mono<ResponseEntity<ItineraryResponse>> updateItem(@PathVariable Long id, @PathVariable Long itemId,
                                                               @RequestBody UpdateItineraryItemRequest request) {
        return Mono.fromCallable(() -> itineraryService.updateItem(id, itemId, request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ItineraryResponse::from)
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}/items/{itemId}")
    public Mono<ResponseEntity<ItineraryResponse>> deleteItem(@PathVariable Long id, @PathVariable Long itemId) {
        return Mono.fromCallable(() -> itineraryService.deleteItem(id, itemId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ItineraryResponse::from)
                .map(ResponseEntity::ok);
    }

    /** 바람개비 상태 조회 - 프론트가 1~5분 주기로 폴링. 캐시된 지역 데이터만 사용해 즉시 응답. */
    @GetMapping("/{id}/trigger-status")
    public Mono<ResponseEntity<TriggerResult>> triggerStatus(@PathVariable Long id) {
        return Mono.fromCallable(() -> itineraryService.get(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(triggerDetectionService::detectForItinerary)
                .map(ResponseEntity::ok);
    }

    /**
     * 바람개비 트리거 대응 대안 코스 추천. 4단계 파이프라인을 재사용하되 avoid로 우선 회피 정렬을 지정한다.
     * 이미 일정에 담긴 장소(고정 여부 무관)는 excludeContentIds로 자동 제외된다.
     */
    @GetMapping("/{id}/alternatives")
    public Mono<ResponseEntity<List<RecommendationCandidate>>> alternatives(
            @PathVariable Long id,
            @RequestParam(required = false) RecommendationRequest.AvoidanceHint avoid,
            @RequestParam(required = false) String seedPlaceName) {
        return Mono.fromCallable(() -> itineraryService.get(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(itinerary -> recommendationPipeline.recommend(buildAlternativeRequest(itinerary, avoid, seedPlaceName)))
                .map(ResponseEntity::ok);
    }

    /**
     * AI 초기 일정 초안 생성 (5단계). 4단계 파이프라인이 검증한 실제 후보 중 상위 placeCount건을
     * LLM이 순서/제안시각만 배정해 돌려준다 - 결과는 바로 저장되지 않고 프론트에서 검토 후 addItem으로 반영.
     */
    @GetMapping("/{id}/auto-plan")
    public Mono<ResponseEntity<List<RecommendationCandidate>>> autoPlan(
            @PathVariable Long id,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "5") int placeCount) {
        return Mono.fromCallable(() -> itineraryService.get(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(itinerary -> initialPlanService.draft(buildAutoPlanRequest(itinerary, tags, query), placeCount))
                .map(ResponseEntity::ok);
    }

    private RecommendationRequest buildAutoPlanRequest(Itinerary itinerary, List<String> tags, String query) {
        List<String> excludeContentIds = itinerary.getItems().stream()
                .map(item -> item.getContentId())
                .collect(Collectors.toList());
        List<String> excludePlaceNames = List.copyOf(tripRecordService.getBadPlaceNames(itinerary.getSessionUuid()));
        return RecommendationRequest.builder()
                .tags(tags)
                .naturalLanguageQuery(query)
                .excludeContentIds(excludeContentIds)
                .excludePlaceNames(excludePlaceNames)
                .build();
    }

    private RecommendationRequest buildAlternativeRequest(Itinerary itinerary,
                                                            RecommendationRequest.AvoidanceHint avoid,
                                                            String seedPlaceName) {
        List<String> excludeContentIds = itinerary.getItems().stream()
                .map(item -> item.getContentId())
                .collect(Collectors.toList());
        List<String> excludePlaceNames = List.copyOf(tripRecordService.getBadPlaceNames(itinerary.getSessionUuid()));
        return RecommendationRequest.builder()
                .seedPlaceName(seedPlaceName)
                .excludeContentIds(excludeContentIds)
                .excludePlaceNames(excludePlaceNames)
                .avoidanceHint(avoid)
                .build();
    }
}
