package com.windmill.controller;

import com.windmill.domain.Itinerary;
import com.windmill.domain.ItineraryItem;
import com.windmill.dto.AddItineraryItemRequest;
import com.windmill.dto.AlertEventResponse;
import com.windmill.dto.AlternativesResponse;
import com.windmill.dto.AnchorPlanRequest;
import com.windmill.dto.ApplyAlternativeRequest;
import com.windmill.dto.ItemCompletionRequest;
import com.windmill.dto.ApplySuggestedRouteRequest;
import com.windmill.dto.ConfirmDayRequest;
import com.windmill.dto.CreateItineraryRequest;
import com.windmill.dto.ItineraryListItemResponse;
import com.windmill.dto.ItineraryResponse;
import com.windmill.dto.ItineraryStatus;
import com.windmill.dto.OngoingItineraryResponse;
import com.windmill.dto.PlaceHoursCheckRequest;
import com.windmill.dto.PlaceHoursCheckResponse;
import com.windmill.dto.RevertPlanRequest;
import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RecommendationRequest;
import com.windmill.dto.SmartPlanResponse;
import com.windmill.dto.SuggestedRouteResponse;
import com.windmill.dto.TriggerResult;
import com.windmill.dto.UpdateItineraryItemRequest;
import com.windmill.service.itinerary.GreedyRouteSuggestService;
import com.windmill.service.itinerary.ItineraryService;
import com.windmill.service.itinerary.PlaceHoursCheckService;
import com.windmill.service.notification.AlertFeedService;
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
    private final PlaceHoursCheckService placeHoursCheckService;
    private final GreedyRouteSuggestService greedyRouteSuggestService;
    private final TriggerDetectionService triggerDetectionService;
    private final RecommendationPipeline recommendationPipeline;
    private final InitialPlanService initialPlanService;
    private final SmartPlanService smartPlanService;
    private final AnchorPlanService anchorPlanService;
    private final TripRecordService tripRecordService;
    private final AlertFeedService alertFeedService;

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

    /**
     * 일정 추가/시간 수정 직전 휴무·마감 경고. 저장을 막지 않으며, 프론트가 confirm 후 add/update 한다.
     */
    @PostMapping("/{id}/hours-check")
    public Mono<ResponseEntity<PlaceHoursCheckResponse>> checkHours(
            @PathVariable Long id,
            @RequestBody PlaceHoursCheckRequest request) {
        return Mono.fromCallable(() -> placeHoursCheckService.check(id, request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
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

    /** 지난 일정(완료) 수동 처리 - 스킵·조기 완료, 또는 완료 항목을 다시 "진행 중"으로 되돌리기 */
    @PatchMapping("/{id}/items/{itemId}/completion")
    public Mono<ResponseEntity<ItineraryResponse>> setItemCompletion(
            @PathVariable Long id, @PathVariable Long itemId,
            @RequestBody ItemCompletionRequest request) {
        return Mono.fromCallable(() -> itineraryService.setItemCompletion(id, itemId, request.isCompleted()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::toResponse)
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{id}/items/{itemId}")
    public Mono<ResponseEntity<ItineraryResponse>> deleteItem(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @RequestParam(defaultValue = "true") boolean reflow,
            @RequestParam(defaultValue = "false") boolean replaceBackup) {
        return Mono.fromCallable(() -> itineraryService.deleteItem(id, itemId, reflow, replaceBackup))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> {
                    ItineraryResponse body = toResponse(result.itinerary());
                    body.setAutoReplacedPlaceName(result.autoReplacedPlaceName());
                    return body;
                })
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

    /**
     * 바람개비 상태 조회 - 프론트가 1~5분 주기로 폴링. 캐시된 지역 데이터만 사용해 즉시 응답.
     * originLon·originLat(WGS84, 현재 위치)을 주면 "다음 장소까지 이동시간" 트리거도 함께 판정한다 -
     * 위치 권한이 없으면 그냥 생략하고 나머지 트리거는 그대로 응답한다.
     */
    @GetMapping("/{id}/trigger-status")
    public Mono<ResponseEntity<TriggerResult>> triggerStatus(
            @PathVariable Long id,
            @RequestParam(required = false) Double originLon,
            @RequestParam(required = false) Double originLat) {
        return Mono.fromCallable(() -> itineraryService.get(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(itinerary -> triggerDetectionService.detectForItinerary(itinerary, originLon, originLat))
                .map(ResponseEntity::ok);
    }

    /**
     * 알림 피드 - 실제로 발송(시도)된 알림 이력을 최신순으로. NotificationSchedulerService가
     * dispatch()를 결정한 시점에만 기록되므로 사용자가 실제로 받은 알림과 항상 일치한다.
     */
    @GetMapping("/{id}/alert-feed")
    public Mono<ResponseEntity<List<AlertEventResponse>>> alertFeed(
            @PathVariable Long id,
            @RequestParam(defaultValue = "30") int limit) {
        return Mono.fromCallable(() -> alertFeedService.list(id, limit))
                .subscribeOn(Schedulers.boundedElastic())
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
                        : avoid == RecommendationRequest.AvoidanceHint.CROWD
                                ? "CROWD_ALTERNATIVE"
                                : avoid == RecommendationRequest.AvoidanceHint.ROUTE
                                        ? "ROUTE_ALTERNATIVE"
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
     * standard=true면 오전·오후 인기 스팟(축제 우선)을 방문일 휴무·마감을 피해 채운다.
     */
    @GetMapping("/{id}/smart-plan")
    public Mono<ResponseEntity<SmartPlanResponse>> smartPlan(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int placeCount,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(defaultValue = "false") boolean standard) {
        return Mono.fromCallable(() -> itineraryService.get(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(itinerary -> standard
                        ? smartPlanService.buildStandardDayPlan(itinerary)
                        : smartPlanService.build(itinerary, placeCount, date))
                .map(ResponseEntity::ok);
    }

    /**
     * 동선 재계산(카카오 이동시간 매트릭스 TSP + 시간표 재생성).
     * originLon·originLat(WGS84)를 주면 GPS를 시작점으로 둔다. startTime("HH:mm")을 주면
     * 첫 장소 도착 시각을 그 시각으로 고정하고 나머지는 그 뒤로 자연스럽게 이어 붙인다.
     */
    @PostMapping("/{id}/optimize-route")
    public Mono<ResponseEntity<ItineraryResponse>> optimizeRoute(
            @PathVariable Long id,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) Double originLon,
            @RequestParam(required = false) Double originLat,
            @RequestParam(required = false) String startTime) {
        return Mono.fromCallable(() -> {
                    ItineraryService.OptimizeRouteResult result =
                            itineraryService.optimizeRoute(id, date, originLon, originLat, startTime);
                    ItineraryResponse body = toResponse(result.itinerary());
                    body.setRouteHint(result.message());
                    body.setOptimizedDistanceKm(result.totalDistanceKm());
                    return body;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    /**
     * 대안 채택 통합 - 기존 항목 삭제 + 대안 추가(+ 선택적 동선 재계산)를 한 트랜잭션으로 하고
     * 변경 이력 한 건을 남긴다. 응답의 originalPlan/changeHistory로 이력 패널을 그린다.
     */
    @PostMapping("/{id}/apply-alternative")
    public Mono<ResponseEntity<ItineraryResponse>> applyAlternative(
            @PathVariable Long id,
            @Valid @RequestBody ApplyAlternativeRequest request) {
        return Mono.fromCallable(() -> {
                    ItineraryService.ApplyAlternativeResult result =
                            itineraryService.applyAlternative(id, request);
                    ItineraryResponse body = toResponse(result.itinerary());
                    body.setAutoReplacedPlaceName(result.newPlaceName());
                    body.setRouteHint(result.routeHint());
                    body.setOptimizedDistanceKm(result.totalDistanceKm());
                    return body;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    /**
     * "동선 다시" 전용 - 동선을 재계산하고 그 자체를 변경 이력(ROUTE)으로 남긴다.
     * 이력을 남기지 않는 일반 재계산은 {@code /optimize-route}를 쓴다.
     */
    @PostMapping("/{id}/apply-reroute")
    public Mono<ResponseEntity<ItineraryResponse>> applyReroute(
            @PathVariable Long id,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) Double originLon,
            @RequestParam(required = false) Double originLat,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String reason) {
        return Mono.fromCallable(() -> {
                    ItineraryService.OptimizeRouteResult result =
                            itineraryService.applyReroute(id, date, originLon, originLat, startTime, reason);
                    ItineraryResponse body = toResponse(result.itinerary());
                    body.setRouteHint(result.message());
                    body.setOptimizedDistanceKm(result.totalDistanceKm());
                    return body;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    /**
     * 되돌리기 - targetSequence가 null이면 원본으로, 아니면 그 번호의 변경 이력으로. 되돌리기 자체도
     * 새 변경 이력(REVERT)으로 쌓인다. 프론트는 확인 모달 후 호출한다.
     */
    @PostMapping("/{id}/revert-plan")
    public Mono<ResponseEntity<ItineraryResponse>> revertPlan(
            @PathVariable Long id,
            @RequestBody(required = false) RevertPlanRequest request) {
        Integer targetSequence = request == null ? null : request.getTargetSequence();
        return Mono.fromCallable(() -> toResponse(itineraryService.revertPlan(id, targetSequence)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    /**
     * "이 순서 어때요?" 미리보기. 현재 위치·시각 기준 그리디 재배열만 계산하고 일정에는 쓰지 않는다.
     */
    @GetMapping("/{id}/suggest-route")
    public Mono<ResponseEntity<SuggestedRouteResponse>> suggestRoute(
            @PathVariable Long id,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) Double originLon,
            @RequestParam(required = false) Double originLat) {
        return Mono.fromCallable(() -> {
                    Itinerary itinerary = itineraryService.get(id);
                    List<ItineraryItem> targets = itinerary.getItems().stream()
                            .filter(i -> date == null
                                    || date.equals(i.getVisitDate())
                                    || (i.getVisitDate() == null && date.equals(itinerary.getStartDate())))
                            .collect(Collectors.toList());
                    return greedyRouteSuggestService.suggest(targets, originLon, originLat);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    /**
     * 제안 순서를 오늘 일정에 반영. 그리디를 다시 돌리지 않고 클라이언트가 확인한 순서·시각만 저장한다.
     */
    @PostMapping("/{id}/apply-suggested-route")
    public Mono<ResponseEntity<ItineraryResponse>> applySuggestedRoute(
            @PathVariable Long id,
            @RequestParam(required = false) LocalDate date,
            @Valid @RequestBody ApplySuggestedRouteRequest request) {
        return Mono.fromCallable(() -> itineraryService.applySuggestedRoute(id, date, request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(this::toResponse)
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
     * 앵커(고정 일정) 등록 - 예: DDP에서 19:00 공연처럼 시각이 정해진 장소를 하루 일정의 기준점으로
     * 삼고 앞뒤 빈 시간대(점심·가벼운 도보 관광 / 저녁 식사·카페)를 TarRlteTarService1(연관 관광지,
     * 앵커 장소 자체가 조회 기준)로 채운 초안을 돌려준다. auto-plan과 동일하게 바로 저장하지 않고
     * 프론트에서 검토(체크박스 해제 가능) 후 addItem으로 반영한다.
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
        ItineraryItem origin = originItem(itinerary);
        return RecommendationRequest.builder()
                .regionCode(itinerary.getSignguFullCode())
                .withPet(itinerary.isWithPet())
                .strollerFriendly(itinerary.isStrollerFriendly())
                .accessibleFriendly(itinerary.isAccessibleFriendly())
                .companionType(itinerary.getCompanionType())
                .adultAgeGroup(itinerary.getAdultAgeGroup())
                .childAges(itinerary.getChildAges())
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
        ItineraryItem origin = originItem(itinerary);
        return RecommendationRequest.builder()
                .regionCode(itinerary.getSignguFullCode())
                .withPet(itinerary.isWithPet())
                .strollerFriendly(itinerary.isStrollerFriendly())
                .accessibleFriendly(itinerary.isAccessibleFriendly())
                .companionType(itinerary.getCompanionType())
                .adultAgeGroup(itinerary.getAdultAgeGroup())
                .childAges(itinerary.getChildAges())
                .seedPlaceName(seedPlaceName)
                .excludeContentIds(excludeContentIds)
                .excludePlaceNames(excludePlaceNames)
                .avoidanceHint(avoid)
                // 대안은 "휴무 아닌 곳 몇 군데 빨리" 보여주면 되는 액션이라, 검색과 동일하게 LLM(Stage4)
                // 문장 생성을 건너뛰고 후보 수도 Stage2/3 전에 잘라 응답을 빠르게 한다.
                .skipLlm(true)
                .originContentId(origin == null ? null : origin.getContentId())
                .originContentTypeId(origin == null ? null : origin.getContentTypeId())
                .build();
    }

    /** 응답 변환 - 상태(ACTIVE/ENDED)를 함께 계산해 내려준다 */
    private ItineraryResponse toResponse(Itinerary itinerary) {
        return itineraryService.toEnrichedResponse(itinerary);
    }

    /**
     * 거리(km)·주변 추천 기준점 - 사용자가 고정(pin)한 장소가 있으면 그걸 최우선으로 삼는다
     * ("여기 근처로 채우고 싶다"는 명시적 의도), 없으면 "지금 있는 곳"을 대신할 정보가 없어
     * 이미 담긴 마지막 장소를 기준으로 삼는다. 여러 곳을 고정했으면 가장 최근(뒤쪽)에 담긴 걸 우선.
     */
    private ItineraryItem originItem(Itinerary itinerary) {
        List<ItineraryItem> items = itinerary.getItems();
        if (items.isEmpty()) {
            return null;
        }
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).isPinned()) {
                return items.get(i);
            }
        }
        return items.get(items.size() - 1);
    }
}
