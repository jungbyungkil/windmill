package com.windmill.controller;

import com.windmill.dto.SharedItineraryResponse;
import com.windmill.service.itinerary.ItineraryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/shares")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ShareController {

    private final ItineraryService itineraryService;

    /** 공유 링크로 열린 공개 일정 조회 (로그인/세션 불필요) */
    @GetMapping("/{token}")
    public Mono<ResponseEntity<SharedItineraryResponse>> get(@PathVariable String token) {
        return Mono.fromCallable(() -> itineraryService.getShared(token))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }
}
