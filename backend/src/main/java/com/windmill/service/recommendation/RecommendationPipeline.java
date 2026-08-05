package com.windmill.service.recommendation;

import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RecommendationRequest;
import com.windmill.dto.RegionCode;
import com.windmill.dto.RelatedCandidate;
import com.windmill.dto.TourAttractionDetail;
import com.windmill.service.region.RegionCodeService;
import com.windmill.service.tourapi.TourAttractionService;
import com.windmill.service.trigger.RegionCondition;
import com.windmill.service.trigger.TriggerScheduler;
import com.windmill.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 4단계 검증 로직 오케스트레이터. AI가 임의로 추천하지 않는다는 것을 코드 구조로 증명하기 위해
 * 각 단계를 명시적인 서비스 클래스 호출로 분리하고, 순서/건수를 로그로 남긴다.
 *
 *   1단계(연관관광지) → 2단계(영업시간) → 3단계(집중률) → 4단계(태그매칭·문장생성, LLM)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationPipeline {

    private final Stage1RelatedAttractionService stage1;
    private final Stage2BusinessHoursFilter stage2;
    private final Stage3CrowdRateFilter stage3;
    private final Stage4TagMatchingService stage4;
    private final RegionCodeService regionCodeService;
    private final TourAttractionService tourAttractionService;
    private final TriggerScheduler triggerScheduler;
    private final BadgeAssembler badgeAssembler;

    public Mono<List<RecommendationCandidate>> recommend(RecommendationRequest request) {
        log.info("[Pipeline] 추천 시작 - region={}, seed={}, tags={}, avoid={}, origin={}",
                request.getRegionCode(), request.getSeedPlaceName(), request.getTags(), request.getAvoidanceHint(),
                request.getOriginContentId());

        RegionCode region = regionCodeService.find(request.getRegionCode())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지역코드: " + request.getRegionCode()));

        Set<String> exclude = request.getExcludeContentIds() == null
                ? Set.of()
                : Set.copyOf(request.getExcludeContentIds());
        Set<String> excludeNames = request.getExcludePlaceNames() == null
                ? Set.of()
                : Set.copyOf(request.getExcludePlaceNames());

        return Mono.zip(resolveOrigin(request), resolveCondition(region))
                .flatMap(tuple -> {
                    TourAttractionDetail origin = tuple.getT1();
                    RegionCondition condition = tuple.getT2();
                    boolean rainAlternative = request.getAvoidanceHint() == RecommendationRequest.AvoidanceHint.WEATHER;
                    Mono<List<RelatedCandidate>> stage1Result = rainAlternative
                            ? stage1.fetchIndoor(region)
                            : stage1.fetch(region, request.getSeedPlaceName(), request.isWithPet())
                                    .flatMap(list -> stage1.resolveContentIds(list, region));
                    return stage1Result
                            .map(list -> list.stream()
                                    .filter(c -> !exclude.contains(c.getContentId()))
                                    .collect(Collectors.toList()))
                            .flatMap(stage2::filter)
                            .map(list -> attachDistance(list, origin))
                            .flatMap(list -> stage3.filter(list, region))
                            .map(list -> CompanionCategoryRanking.rank(list, request.getCompanionType()))
                            .flatMap(list -> stage4.match(list, request.getTags(), request.getNaturalLanguageQuery()))
                            .doOnNext(list -> badgeAssembler.attach(list, condition));
                })
                .map(list -> list.stream()
                        .filter(c -> !excludeNames.contains(c.getPlaceName()))
                        .collect(Collectors.toList()))
                .map(list -> applyAvoidanceOrdering(list, request.getAvoidanceHint()))
                .doOnNext(list -> log.info("[Pipeline] 최종 추천 {}건", list.size()));
    }

    /** origin이 없을 때(또는 조회 실패) 쓰는 빈 상세 - Reactor Mono는 null을 emit할 수 없어 null 대신 빈 객체로 흘린다 */
    private static final TourAttractionDetail NO_ORIGIN = TourAttractionDetail.builder().build();
    /** 날씨/집중률 조회 실패 시 배지를 그냥 생략하기 위한 빈 상태 - TriggerScheduler와 동일한 no-null 관례 */
    private static final RegionCondition NO_CONDITION = RegionCondition.builder().crowdRateByPlaceName(Map.of()).build();

    /** 배지 조립용 지역 상태(날씨 POP/관광지별 집중률) - TriggerDetectionService와 동일하게 30분 캐시를 공유한다 */
    private Mono<RegionCondition> resolveCondition(RegionCode region) {
        return triggerScheduler.ensureFresh(region).onErrorReturn(NO_CONDITION);
    }

    /** originContentId가 있으면 그 장소의 상세(mapX/mapY)를 조회해 거리 계산 기준점으로 삼는다 - 30분 캐시라 저렴 */
    private Mono<TourAttractionDetail> resolveOrigin(RecommendationRequest request) {
        if (request.getOriginContentId() == null || request.getOriginContentTypeId() == null) {
            return Mono.just(NO_ORIGIN);
        }
        return tourAttractionService.getDetail(request.getOriginContentId(), request.getOriginContentTypeId())
                .defaultIfEmpty(NO_ORIGIN)
                .onErrorReturn(NO_ORIGIN);
    }

    /** origin의 mapX/mapY가 없으면(또는 조회 실패) distanceKm은 null로 남는다 - 프론트에서 뱃지를 숨긴다 */
    private List<RelatedCandidate> attachDistance(List<RelatedCandidate> candidates, TourAttractionDetail origin) {
        for (RelatedCandidate c : candidates) {
            c.setDistanceKm(GeoUtils.distanceKmSafe(origin.getMapX(), origin.getMapY(), c.getMapX(), c.getMapY()));
        }
        return candidates;
    }

    /**
     * 트리거 우선회피 정렬. 혼잡도 트리거는 Stage3에서 이미 여유율 순으로 정렬되어 있으므로 그대로 두고,
     * 기상 트리거는 #실내 태그가 매칭된 후보를 앞으로 당긴다.
     */
    private List<RecommendationCandidate> applyAvoidanceOrdering(List<RecommendationCandidate> candidates,
                                                                   RecommendationRequest.AvoidanceHint hint) {
        if (hint == RecommendationRequest.AvoidanceHint.WEATHER) {
            return candidates.stream()
                    .sorted(Comparator.comparing((RecommendationCandidate c) ->
                            c.getMatchedTags() != null && c.getMatchedTags().contains("#실내") ? 0 : 1))
                    .collect(Collectors.toList());
        }
        return candidates;
    }
}
