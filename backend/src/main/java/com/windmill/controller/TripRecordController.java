package com.windmill.controller;

import com.windmill.dto.CreateTripRecordRequest;
import com.windmill.dto.RegionTripHighlightResponse;
import com.windmill.dto.TripRecordResponse;
import com.windmill.service.trip.TripRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/trip-records")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TripRecordController {

    private final TripRecordService tripRecordService;

    @PostMapping
    public Mono<ResponseEntity<TripRecordResponse>> create(
            @RequestHeader("X-Session-Id") String sessionId,
            @RequestBody CreateTripRecordRequest request) {
        return Mono.fromCallable(() -> tripRecordService.create(sessionId, request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(TripRecordResponse::from)
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
}
