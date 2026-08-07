package com.windmill.controller;

import com.windmill.domain.CompanionType;
import com.windmill.dto.CreateTripRecordRequest;
import com.windmill.dto.RecommendedScheduleResponse;
import com.windmill.dto.RegionTripHighlightResponse;
import com.windmill.dto.TripRecordResponse;
import com.windmill.dto.TripStoryFeedResponse;
import com.windmill.service.trip.CommunityScheduleService;
import com.windmill.service.trip.TripRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/trip-records")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TripRecordController {

    private final TripRecordService tripRecordService;
    private final CommunityScheduleService communityScheduleService;

    @PostMapping
    public Mono<ResponseEntity<TripRecordResponse>> create(
            @RequestHeader("X-Session-Id") String sessionId,
            @RequestBody CreateTripRecordRequest request) {
        return Mono.fromCallable(() -> tripRecordService.create(sessionId, request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(TripRecordResponse::from)
                .map(ResponseEntity::ok);
    }

    /** 첫 화면 인기 여행 기록 피드 - 선택 지역 우선, 평점·좋아요·클릭 순 상위 5건 */
    @GetMapping("/feed")
    public Mono<ResponseEntity<List<TripStoryFeedResponse>>> feed(
            @RequestParam(required = false) String signguFullCode) {
        return Mono.fromCallable(() -> tripRecordService.findPopularStories(signguFullCode))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{id}/like")
    public Mono<ResponseEntity<TripStoryFeedResponse>> like(@PathVariable Long id) {
        return Mono.fromCallable(() -> tripRecordService.incrementLike(id))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    @PostMapping("/{id}/click")
    public Mono<ResponseEntity<TripStoryFeedResponse>> click(@PathVariable Long id) {
        return Mono.fromCallable(() -> tripRecordService.incrementClick(id))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    /** 이 지역으로 떠나는 다른 여행자에게 보여줄 최근 "엄지척" 여행 기록 최대 5건 - 세션 무관, 공개 조회 */
    @GetMapping("/region/{signguFullCode}/highlights")
    public Mono<ResponseEntity<List<RegionTripHighlightResponse>>> highlights(@PathVariable String signguFullCode) {
        return Mono.fromCallable(() -> tripRecordService.findRecentGoodTripsByRegion(signguFullCode))
                .subscribeOn(Schedulers.boundedElastic())
                .map(list -> list.stream().map(RegionTripHighlightResponse::from).collect(Collectors.toList()))
                .map(ResponseEntity::ok);
    }

    /**
     * 같은 지역을 다녀간 여행자들의 기록 기반 추천 일정. basedOnRecordCount가 콜드스타트 임계값 미만이면
     * days가 빈 리스트로 온다 - 프론트는 이 경우 기존 /api/recommendations 흐름으로 폴백해야 한다.
     */
    @GetMapping("/recommendation")
    public Mono<ResponseEntity<RecommendedScheduleResponse>> recommendation(
            @RequestParam String regionCode,
            @RequestParam(required = false) CompanionType companionType,
            @RequestParam(required = false) LocalDate startDate) {
        return communityScheduleService.recommend(regionCode, companionType, startDate)
                .map(ResponseEntity::ok);
    }
}
