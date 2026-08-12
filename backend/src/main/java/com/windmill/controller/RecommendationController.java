package com.windmill.controller;

import com.windmill.domain.CompanionType;
import com.windmill.dto.CategoryPlaceGroup;
import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RecommendationRequest;
import com.windmill.service.recommendation.CategoryRecommendationService;
import com.windmill.service.recommendation.RecommendationPipeline;
import com.windmill.service.trip.TripRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class RecommendationController {

    private final RecommendationPipeline recommendationPipeline;
    private final CategoryRecommendationService categoryRecommendationService;
    private final TripRecordService tripRecordService;

    /**
     * 카테고리별 장소 추천 (식당/박물관/키즈카페/카페).
     * TourAPI + 집중률(방문자) 데이터 기반, DB 미사용. 방문자 많은 순 정렬.
     */
    @GetMapping("/by-category")
    public Mono<ResponseEntity<List<CategoryPlaceGroup>>> byCategory(
            @RequestParam String regionCode,
            @RequestParam(required = false) List<String> excludeContentIds) {
        Set<String> exclude = excludeContentIds == null ? Set.of() : new HashSet<>(excludeContentIds);
        return categoryRecommendationService.recommendByCategory(regionCode, exclude)
                .map(ResponseEntity::ok);
    }

    @GetMapping
    public Mono<ResponseEntity<List<RecommendationCandidate>>> recommend(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam String regionCode,
            @RequestParam(required = false, defaultValue = "false") boolean withPet,
            @RequestParam(required = false, defaultValue = "false") boolean strollerFriendly,
            @RequestParam(required = false, defaultValue = "false") boolean accessibleFriendly,
            @RequestParam(required = false) CompanionType companionType,
            @RequestParam(required = false) String seedPlaceName,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) List<String> excludeContentIds,
            @RequestParam(required = false) String originContentId,
            @RequestParam(required = false) Integer originContentTypeId) {

        Mono<List<String>> badPlaceNamesMono = sessionId == null || sessionId.isBlank()
                ? Mono.just(List.of())
                : Mono.fromCallable(() -> tripRecordService.getBadPlaceNames(sessionId))
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(List::copyOf);

        return badPlaceNamesMono.flatMap(badPlaceNames -> {
            RecommendationRequest request = RecommendationRequest.builder()
                    .regionCode(regionCode)
                    .withPet(withPet)
                    .strollerFriendly(strollerFriendly)
                    .accessibleFriendly(accessibleFriendly)
                    .companionType(companionType)
                    .seedPlaceName(seedPlaceName)
                    .tags(tags)
                    .naturalLanguageQuery(query)
                    .excludeContentIds(excludeContentIds)
                    .excludePlaceNames(badPlaceNames)
                    .originContentId(originContentId)
                    .originContentTypeId(originContentTypeId)
                    .build();

            return recommendationPipeline.recommend(request);
        }).map(ResponseEntity::ok);
    }
}
