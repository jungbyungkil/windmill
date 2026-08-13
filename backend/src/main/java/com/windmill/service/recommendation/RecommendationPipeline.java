package com.windmill.service.recommendation;

import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RecommendationRequest;
import com.windmill.dto.RegionCode;
import com.windmill.dto.RelatedCandidate;
import com.windmill.dto.TourAttractionDetail;
import com.windmill.domain.RecommendThemeTag;
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
                    boolean heatAlternative = request.getAvoidanceHint() == RecommendationRequest.AvoidanceHint.HEAT;
                    String seed = firstNonBlank(request.getSeedPlaceName(), request.getNaturalLanguageQuery());
                    List<RecommendThemeTag> themes = RecommendThemeTag.resolve(request.getTags(), seed);
                    Mono<List<RelatedCandidate>> stage1Result;
                    if (heatAlternative) {
                        stage1Result = stage1.fetchIndoorForHeat(region);
                    } else if (rainAlternative) {
                        stage1Result = stage1.fetchIndoor(region);
                    } else if (!themes.isEmpty()) {
                        // UI 해시태그는 TourAPI(KorService2) 테마 조회로 최소 후보를 확보
                        stage1Result = stage1.fetchByThemes(region, request.getTags(), seed);
                    } else {
                        stage1Result = stage1.fetch(region, seed, request.isWithPet())
                                .flatMap(list -> stage1.resolveContentIds(list, region));
                    }
                    return stage1Result
                            .map(list -> list.stream()
                                    .filter(c -> !exclude.contains(c.getContentId()))
                                    .collect(Collectors.toList()))
                            .flatMap(stage2::filter)
                            .map(list -> attachDistance(list, origin))
                            .flatMap(list -> stage3.filter(list, region))
                            .map(list -> CompanionCategoryRanking.rank(list, request.getCompanionType()))
                            .map(list -> AccessibilityRanking.rank(list, request.isStrollerFriendly(), request.isAccessibleFriendly()))
                            .flatMap(list -> stage4.match(list, request.getTags(), request.getNaturalLanguageQuery()))
                            .map(list -> enrichThemeTags(list, themes))
                            .doOnNext(list -> badgeAssembler.attach(list, condition));
                })
                .map(list -> list.stream()
                        .filter(c -> !excludeNames.contains(c.getPlaceName()))
                        .collect(Collectors.toList()))
                .map(list -> applyAvoidanceOrdering(list, request.getAvoidanceHint()))
                .doOnNext(list -> log.info("[Pipeline] 최종 추천 {}건", list.size()));
    }

    /**
     * 장소명 직접 검색 - "이미 가려는 곳이 정해진" 사용자를 위한 경로. 연관추천(Stage1 seed)이 아니라
     * KorService2 키워드 검색으로 그 이름 자체를 찾는다. Stage3(집중률 정렬)·Stage4(LLM 태그/문장)는
     * 건너뛴다 - 사용자가 정확한 이름으로 찾는 결과이지 취향 추천이 아니라 굳이 LLM이 필요 없다.
     */
    public Mono<List<RecommendationCandidate>> searchByName(String regionCode, String query) {
        RegionCode region = regionCodeService.find(regionCode)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지역코드: " + regionCode));
        return stage1.searchByName(region, query)
                .flatMap(stage2::filter)
                .flatMap(list -> resolveCondition(region).map(condition -> {
                    List<RecommendationCandidate> mapped = list.stream()
                            .map(RecommendationPipeline::toSearchCandidate)
                            .collect(Collectors.toList());
                    badgeAssembler.attach(mapped, condition);
                    return mapped;
                }));
    }

    private static RecommendationCandidate toSearchCandidate(RelatedCandidate c) {
        return RecommendationCandidate.builder()
                .contentId(c.getContentId())
                .contentTypeId(c.getContentTypeId())
                .placeName(c.getPlaceName())
                .category(c.getCategoryLcls())
                .thumbnailUrl(c.getThumbnailUrl())
                .matchedTags(List.of())
                .rank(c.getRank())
                .addr1(c.getAddr1())
                .tel(c.getTel())
                .isFree(c.getIsFree())
                .useFeeText(c.getUseFeeText())
                .restDateText(c.getRestDateText())
                .closeTime(c.getCloseTime())
                .useTimeText(c.getUseTimeText())
                .homepageUrl(c.getHomepageUrl())
                .mapX(c.getMapX())
                .mapY(c.getMapY())
                .businessOpen(c.getBusinessOpen())
                .businessStatus(c.getBusinessStatus())
                .strollerText(c.getStrollerText())
                .strollerFriendly(c.getStrollerFriendly())
                .accessibleFriendly(c.isAccessibleFriendly())
                .build();
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
     * 비/폭염 트리거는 #실내 태그가 매칭된 후보를 앞으로 당긴다.
     */
    private List<RecommendationCandidate> applyAvoidanceOrdering(List<RecommendationCandidate> candidates,
                                                                   RecommendationRequest.AvoidanceHint hint) {
        if (hint == RecommendationRequest.AvoidanceHint.WEATHER
                || hint == RecommendationRequest.AvoidanceHint.HEAT) {
            return candidates.stream()
                    .sorted(Comparator.comparing((RecommendationCandidate c) ->
                            c.getMatchedTags() != null && c.getMatchedTags().contains("#실내") ? 0 : 1))
                    .collect(Collectors.toList());
        }
        return candidates;
    }

    /** #맛집 태그 또는 식당/맛집/레스토랑 등 검색어면 음식점 전용 Stage1 경로 */
    static boolean isFoodIntent(List<String> tags, String query) {
        return RecommendThemeTag.resolve(tags, query).contains(RecommendThemeTag.FOOD);
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return null;
    }

    /** 테마 Stage1 경로에서 LLM 태그가 비어도 선택한 해시태그가 보이도록 보정 */
    private List<RecommendationCandidate> enrichThemeTags(List<RecommendationCandidate> candidates,
                                                          List<RecommendThemeTag> themes) {
        if (themes == null || themes.isEmpty()) {
            return candidates;
        }
        for (RecommendationCandidate c : candidates) {
            List<String> matched = c.getMatchedTags() == null
                    ? new java.util.ArrayList<>()
                    : new java.util.ArrayList<>(c.getMatchedTags());
            for (RecommendThemeTag theme : themes) {
                if (!matched.contains(theme.getTag())) {
                    matched.add(theme.getTag());
                }
            }
            c.setMatchedTags(matched);
            if ((c.getCategory() == null || c.getCategory().isBlank()) && themes.size() == 1) {
                c.setCategory(themes.get(0).getLabel());
            }
        }
        return candidates;
    }
}
