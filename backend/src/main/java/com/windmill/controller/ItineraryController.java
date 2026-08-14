package com.windmill.controller;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.AddItineraryItemRequest;
import com.windmill.dto.AlternativesResponse;
import com.windmill.dto.AnchorPlanRequest;
import com.windmill.dto.ConfirmDayRequest;
import com.windmill.dto.CreateItineraryRequest;
import com.windmill.dto.ItineraryListItemResponse;
import com.windmill.dto.ItineraryResponse;
import com.windmill.dto.ItineraryStatus;
import com.windmill.dto.OngoingItineraryResponse;
import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RecommendationRequest;
import com.windmill.dto.SmartPlanResponse;
import com.windmill.dto.TriggerResult;
import com.windmill.dto.UpdateItineraryItemRequest;
import com.windmill.service.itinerary.ItineraryService;
import com.windmill.service.recommendation.AnchorPlanService;
import com.windmill.service.recommendation.InitialPlanService;
import com.windmill.service.recommendation.RecommendationPipeline;
import com.windmill.service.recommendation.SmartPlanService;
import com.windmill.service.trigger.TriggerDetectionService;
import com.windmill.service.trip.TripRecordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
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
    private final SmartPlanService smartPlanService;
    private final AnchorPlanService anchorPlanService;
    private final TripRecordService tripRecordService;

    @PostMapping
    public Mono<ResponseEntity<ItineraryResponse>> create(
            @RequestHeader("X-Session-Id") String sessionId,
            @Valid @RequestBody CreateItineraryRequest request) {
        return Mono.fromCallable(() -> itineraryService.create(sessionId, request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::toResponse)
                .map(ResponseEntity::ok);
    }

    /** 세션의 미완료 당일치기 목록 - 메인 "진행 중인 여행" 이어하기 */
    @GetMapping("/ongoing")
    public Mono<ResponseEntity<List<OngoingItineraryResponse>>> ongoing(
            @RequestHeader("X-Session-Id") String sessionId) {
        return Mono.fromCallable(() -> itineraryService.findOngoingDayTrips(sessionId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(list -> list.stream().map(OngoingItineraryResponse::from).collect(Collectors.toList()))
                .map(ResponseEntity::ok);
    }

    /** GNB "내 여행 관리" 전체 목록 - ACTIVE/ENDED 통합, status로 필터, limit으로 최근 N건만 */
    @GetMapping
    public Mono<ResponseEntity<List<ItineraryListItemResponse>>> listAll(
            @RequestHeader("X-Session-Id") String sessionId,
            @RequestParam(required = false) ItineraryStatus status,
            @RequestParam(defaultValue = "50") int limit) {
        return Mono.fromCallable(() -> itineraryService.listAll(sessionId, status, limit))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<ItineraryResponse>> get(@PathVariable Long id) {
        return Mono.fromCallable(() -> itineraryService.get(id))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::toResponse)
                .map(ResponseEntity::ok);
    }

    /** GNB "내 여행 관리" 정리(중복 정리 등) - 마무리 기록이 있으면 함께 삭제 */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable Long id) {
        return Mono.<Void>fromRunnable(() -> itineraryService.delete(id))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(ResponseEntity.noContent().build());
    }

    @PostMapping("/{id}/items")
    public Mono<ResponseEntity<ItineraryResponse>> addItem(@PathVariable Long id,
                                                            @Valid @RequestBody AddItineraryItemRequest request) {
        return Mono.fromCallable(() -> itineraryService.addItem(id, request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::toResponse)
                .map(ResponseEntity::ok);
    }

    @PatchMapping("/{id}/items/{itemId}")
    public Mono<ResponseEntity<ItineraryResponse>> updateItem(@PathVariable Long id, @PathVariable Long itemId,
                                                               @RequestBody UpdateItineraryItemRequest request) {
        return Mono.fromCallable(() -> itineraryService.updateItem(id, itemId, request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::toResponse)
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}/items/{itemId}")
    public Mono<ResponseEntity<ItineraryResponse>> deleteItem(@PathVariable Long id, @PathVariable Long itemId) {
        return Mono.fromCallable(() -> itineraryService.deleteItem(id, itemId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::toResponse)
                .map(ResponseEntity::ok);
    }

    /** 일자별 페이지 확정/해제 - 확정해야 프론트가 "다음 날 보기"로 이동을 허용한다 */
    @PatchMapping("/{id}/days/{date}")
    public Mono<ResponseEntity<ItineraryResponse>> confirmDay(@PathVariable Long id, @PathVariable LocalDate date,
                                                                @RequestBody ConfirmDayRequest request) {
        return Mono.fromCallable(() -> itineraryService.confirmDay(id, date, request.isConfirmed()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::toResponse)
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
    public Mono<ResponseEntity<AlternativesResponse>> alternatives(
            @PathVariable Long id,
            @RequestParam(required = false) RecommendationRequest.AvoidanceHint avoid,
            @RequestParam(required = false) String seedPlaceName) {
        final String reason = avoid == RecommendationRequest.AvoidanceHint.WEATHER
                ? "RAIN_ALTERNATIVE"
                : avoid == RecommendationRequest.AvoidanceHint.HEAT
                        ? "HEAT_ALTERNATIVE"
                        : null;
        return Mono.fromCallable(() -> itineraryService.get(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(itinerary -> recommendationPipeline.recommend(buildAlternativeRequest(itinerary, avoid, seedPlaceName)))
                .map(candidates -> AlternativesResponse.builder().candidates(candidates).reason(reason).build())
                .map(ResponseEntity::ok);
    }

    /**
     * 핵심 스마트 일정: TourAPI 후보 → 혼잡↓ 필터 → 날씨 실내 전환 → 동선 최적화 → 시각 배정.
     * AI가 장소를 만들지 않으며, 검증된 API 데이터만 사용한다.
     */
    @GetMapping("/{id}/smart-plan")
    public Mono<ResponseEntity<SmartPlanResponse>> smartPlan(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int placeCount,
            @RequestParam(required = false) LocalDate date) {
        return Mono.fromCallable(() -> itineraryService.get(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(itinerary -> smartPlanService.build(itinerary, placeCount, date))
                .map(ResponseEntity::ok);
    }

    /**
     * 동선 재계산(카카오 이동시간 매트릭스 TSP + 시간표 재생성).
     * originLon·originLat(WGS84)를 주면 GPS를 시작점으로 둔다.
     */
    @PostMapping("/{id}/optimize-route")
    public Mono<ResponseEntity<ItineraryResponse>> optimizeRoute(
            @PathVariable Long id,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) Double originLon,
            @RequestParam(required = false) Double originLat) {
        return Mono.fromCallable(() -> {
                    ItineraryService.OptimizeRouteResult result =
                            itineraryService.optimizeRoute(id, date, originLon, originLat);
                    ItineraryResponse body = toResponse(result.itinerary());
                    body.setRouteHint(result.message());
                    body.setOptimizedDistanceKm(result.totalDistanceKm());
                    return body;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    /** 해당 일자 일정을 방문 시각(HH:mm) 순으로 displayOrder 재정렬 */
    @PostMapping("/{id}/sort-by-time")
    public Mono<ResponseEntity<ItineraryResponse>> sortByTime(
            @PathVariable Long id,
            @RequestParam(required = false) LocalDate date) {
        return Mono.fromCallable(() -> itineraryService.sortByScheduledTime(id, date))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::toResponse)
                .map(ResponseEntity::ok);
    }

    /** 완성 일정 공유 토큰 발급 */
    @PostMapping("/{id}/share")
    public Mono<ResponseEntity<com.windmill.dto.SharedItineraryResponse>> share(@PathVariable Long id) {
        return Mono.fromCallable(() -> itineraryService.createShare(id))
                .subscribeOn(Schedulers.boundedElastic())
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

    /**
     * 목적지 직접 선택 플로우 - 사용자가 이름으로 검색해 고른 장소(anchor)를 오전/오후에 배치하고
     * 앞뒤 빈 시간대(점심·주변 전시관/박물관·저녁 식사/카페)를 4단계 파이프라인으로 채운 초안을 돌려준다.
     * auto-plan과 동일하게 바로 저장하지 않고, 프론트에서 검토(체크박스 해제 가능) 후 addItem으로 반영한다.
     */
    @PostMapping("/{id}/anchor-plan")
    public Mono<ResponseEntity<List<RecommendationCandidate>>> anchorPlan(
            @PathVariable Long id,
            @RequestBody AnchorPlanRequest request) {
        return Mono.fromCallable(() -> itineraryService.get(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(itinerary -> anchorPlanService.buildPlan(itinerary, request))
                .map(ResponseEntity::ok);
    }

    private RecommendationRequest buildAutoPlanRequest(Itinerary itinerary, List<String> tags, String query) {
        List<String> excludeContentIds = itinerary.getItems().stream()
                .map(item -> item.getContentId())
                .collect(Collectors.toList());
        List<String> excludePlaceNames = List.copyOf(tripRecordService.getBadPlaceNames(itinerary.getSessionUuid()));
        ItineraryItem origin = lastItem(itinerary);
        return RecommendationRequest.builder()
                .regionCode(itinerary.getSignguFullCode())
                .withPet(itinerary.isWithPet())
                .strollerFriendly(itinerary.isStrollerFriendly())
                .accessibleFriendly(itinerary.isAccessibleFriendly())
                .companionType(itinerary.getCompanionType())
                .tags(tags)
                .naturalLanguageQuery(query)
                .excludeContentIds(excludeContentIds)
                .excludePlaceNames(excludePlaceNames)
                .originContentId(origin == null ? null : origin.getContentId())
                .originContentTypeId(origin == null ? null : origin.getContentTypeId())
                .build();
    }

    private RecommendationRequest buildAlternativeRequest(Itinerary itinerary,
                                                            RecommendationRequest.AvoidanceHint avoid,
                                                            String seedPlaceName) {
        List<String> excludeContentIds = itinerary.getItems().stream()
                .map(item -> item.getContentId())
                .collect(Collectors.toList());
        List<String> excludePlaceNames = List.copyOf(tripRecordService.getBadPlaceNames(itinerary.getSessionUuid()));
        ItineraryItem origin = lastItem(itinerary);
        return RecommendationRequest.builder()
                .regionCode(itinerary.getSignguFullCode())
                .withPet(itinerary.isWithPet())
                .strollerFriendly(itinerary.isStrollerFriendly())
                .accessibleFriendly(itinerary.isAccessibleFriendly())
                .companionType(itinerary.getCompanionType())
                .seedPlaceName(seedPlaceName)
                .excludeContentIds(excludeContentIds)
                .excludePlaceNames(excludePlaceNames)
                .avoidanceHint(avoid)
                .originContentId(origin == null ? null : origin.getContentId())
                .originContentTypeId(origin == null ? null : origin.getContentTypeId())
                .build();
    }

    /** 응답 변환 - 상태(ACTIVE/ENDED)를 함께 계산해 내려준다 */
    private ItineraryResponse toResponse(Itinerary itinerary) {
        return ItineraryResponse.from(itinerary, itineraryService.statusOf(itinerary));
    }

    /** 거리(km) 표시 기준점 - "지금 있는 곳"을 대신할 정보가 없어 이미 담긴 마지막 장소를 기준으로 삼는다 */
    private ItineraryItem lastItem(Itinerary itinerary) {
        List<ItineraryItem> items = itinerary.getItems();
        return items.isEmpty() ? null : items.get(items.size() - 1);
    }
}
