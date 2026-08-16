package com.windmill.controller;

import com.windmill.domain.AgeGroup;
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

    /**
     * 장소명 직접 검색 - 가고 싶은 곳이 이미 정해진 사용자가 이름으로 찾아 앵커로 등록할 수 있게 한다.
     * (예: "DDP"). 취향 추천이 아니라 정확한 이름 매칭이라 태그/자연어 추천과는 별도 경로.
     */
    @GetMapping("/search")
    public Mono<ResponseEntity<List<RecommendationCandidate>>> search(
            @RequestParam String regionCode,
            @RequestParam String query) {
        return recommendationPipeline.searchByName(regionCode, query).map(ResponseEntity::ok);
    }

    /**
     * 카카오 검색 결과(/search)에서 사용자가 실제로 고른 후보 하나를 TourAPI와 이름 매칭해
     * contentId 등을 채운다. 매칭 실패 시 빈 배열(프론트가 안내 메시지 표시).
     */
    @GetMapping("/resolve")
    public Mono<ResponseEntity<List<RecommendationCandidate>>> resolve(
            @RequestParam String regionCode,
            @RequestParam String placeName,
            @RequestParam(required = false) String mapX,
            @RequestParam(required = false) String mapY) {
        return recommendationPipeline.resolveByName(regionCode, placeName, mapX, mapY).map(ResponseEntity::ok);
    }

    @GetMapping
    public Mono<ResponseEntity<List<RecommendationCandidate>>> recommend(
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestParam String regionCode,
            @RequestParam(required = false, defaultValue = "false") boolean withPet,
            @RequestParam(required = false, defaultValue = "false") boolean strollerFriendly,
            @RequestParam(required = false, defaultValue = "false") boolean accessibleFriendly,
            @RequestParam(required = false) CompanionType companionType,
            @RequestParam(required = false) AgeGroup adultAgeGroup,
            @RequestParam(required = false) List<Integer> childAges,
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
                    .adultAgeGroup(adultAgeGroup)
                    .childAges(childAges)
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
