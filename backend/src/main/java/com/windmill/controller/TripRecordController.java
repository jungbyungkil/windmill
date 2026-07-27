package com.windmill.controller;

import com.windmill.dto.CreateTripRecordRequest;
import com.windmill.dto.TripRecordResponse;
import com.windmill.service.trip.TripRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
}
