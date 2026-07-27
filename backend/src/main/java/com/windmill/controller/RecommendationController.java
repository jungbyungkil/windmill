package com.windmill.controller;

import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RecommendationRequest;
import com.windmill.service.recommendation.RecommendationPipeline;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class RecommendationController {

    private final RecommendationPipeline recommendationPipeline;

    @GetMapping
    public Mono<ResponseEntity<List<RecommendationCandidate>>> recommend(
            @RequestParam(required = false) String seedPlaceName,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) List<String> excludeContentIds) {

        RecommendationRequest request = RecommendationRequest.builder()
                .seedPlaceName(seedPlaceName)
                .tags(tags)
                .naturalLanguageQuery(query)
                .excludeContentIds(excludeContentIds)
                .build();

        return recommendationPipeline.recommend(request).map(ResponseEntity::ok);
    }
}
