package com.windmill.controller;

import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RecommendationRequest;
import com.windmill.service.recommendation.RecommendationPipeline;
import com.windmill.service.trip.TripRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class RecommendationController {

    private final RecommendationPipeline recommendationPipeline;
    private final TripRecordService tripRecordService;

    @GetMapping
    public Mono<ResponseEntity<List<RecommendationCandidate>>> recommend(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam(required = false) String seedPlaceName,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) List<String> excludeContentIds) {

        Mono<List<String>> badPlaceNamesMono = sessionId == null || sessionId.isBlank()
                ? Mono.just(List.of())
                : Mono.fromCallable(() -> tripRecordService.getBadPlaceNames(sessionId))
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(List::copyOf);

        return badPlaceNamesMono.flatMap(badPlaceNames -> {
            RecommendationRequest request = RecommendationRequest.builder()
                    .seedPlaceName(seedPlaceName)
                    .tags(tags)
                    .naturalLanguageQuery(query)
                    .excludeContentIds(excludeContentIds)
                    .excludePlaceNames(badPlaceNames)
                    .build();

            return recommendationPipeline.recommend(request);
        }).map(ResponseEntity::ok);
    }
}
