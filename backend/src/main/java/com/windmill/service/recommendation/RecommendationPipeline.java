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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
                        Mono<List<RelatedCandidate>> themed = stage1.fetchByThemes(region, request.getTags(), seed);
                        // withPet은 예전엔 태그가 있으면 통째로 무시됐다(2026-08-20 사용자 제보 -
                        // "당일치기 시작하기"는 항상 태그를 넘겨서 반려동물 체크박스가 결과에 전혀
                        // 영향을 못 줬음) - 반려동물 인증 후보(KorPetTourService2)를 테마 후보와
                        // 병합해서, "구조화 데이터 우선"(AgeGroupRanking과 동일 원칙) 순으로 앞세운다.
                        stage1Result = request.isWithPet()
                                ? Mono.zip(stage1.fetchPetFriendly(region, seed), themed)
                                        .map(petTuple -> mergePetFirst(petTuple.getT1(), petTuple.getT2()))
                                : themed;
                    } else {
                        stage1Result = stage1.fetch(region, seed, request.isWithPet())
                                .flatMap(list -> stage1.resolveContentIds(list, region));
                    }
                    return stage1Result
                            .map(list -> list.stream()
                                    .filter(c -> !exclude.contains(c.getContentId()))
                                    .collect(Collectors.toList()))
                            .map(list -> request.isSkipLlm() ? capForSpeed(list) : list)
                            .flatMap(stage2::filter)
                            .map(list -> attachDistance(list, origin))
                            .flatMap(list -> stage3.filter(list, region))
                            .map(list -> CompanionCategoryRanking.rank(list, request.getCompanionType()))
                            .map(list -> AccessibilityRanking.rank(list, request.isStrollerFriendly(), request.isAccessibleFriendly()))
                            .map(list -> AgeGroupRanking.rank(list, request.getAdultAgeGroup(), request.getChildAges()))
                            .map(ReservationRequiredRanking::rank)
                            .map(ProximityRanking::rank)
                            .flatMap(list -> request.isSkipLlm()
                                    ? stage4.matchWithoutLlm(list, request.getTags(), request.getChildAges())
                                    : stage4.match(list, request.getTags(), request.getNaturalLanguageQuery(), request.getChildAges()))
                            .map(list -> enrichThemeTags(list, themes))
                            .doOnNext(list -> badgeAssembler.attach(list, condition));
                })
                .map(list -> list.stream()
                        .filter(c -> !excludeNames.contains(c.getPlaceName()))
                        .filter(c -> withinBudget(c, request.getMaxBudgetPerPerson()))
                        .collect(Collectors.toList()))
                .map(list -> applyAvoidanceOrdering(list, request.getAvoidanceHint(), request.getChildAges()))
                .doOnNext(list -> log.info("[Pipeline] 최종 추천 {}건", list.size()));
    }

    /** skipLlm(속도 우선) 요청 전용 - Stage2(영업시간 상세조회, 외부 API) 대상 건수를 줄여 속도를
     *  확보한다(2026-08-16 실측: 후보 20건 기준 Stage2가 약 16초 - 표준 4단계 일정은 어차피 이 중
     *  1곳만 선택하므로 상위 후보만 미리 조회해도 충분함). Stage1이 이미 관련도순으로 정렬해 반환한다. */
    private static final int SPEED_MODE_CANDIDATE_CAP = 8;

    private static List<RelatedCandidate> capForSpeed(List<RelatedCandidate> list) {
        return list.size() > SPEED_MODE_CANDIDATE_CAP
                ? new java.util.ArrayList<>(list.subList(0, SPEED_MODE_CANDIDATE_CAP))
                : list;
    }

    /**
     * 장소명 직접 검색 - "이미 가려는 곳이 정해진" 사용자를 위한 경로(앵커 등록에도 재사용). 연관추천
     * (Stage1 seed)이 아니라 KorService2 키워드 검색으로 그 이름 자체를 찾는다. Stage3(집중률 조회)·
     * Stage4(LLM 태그/문장)는 건너뛴다 - 정확한 이름 매칭이지 취향 추천이 아니라 굳이 LLM이 필요 없다.
     */
    public Mono<List<RecommendationCandidate>> searchByName(String regionCode, String query) {
        RegionCode region = regionCodeService.find(regionCode)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지역코드: " + regionCode));
        return stage1.searchByNameViaKakao(region, query)
                .flatMap(stage2::filter)
                .flatMap(list -> resolveCondition(region).map(condition -> {
                    List<RecommendationCandidate> mapped = list.stream()
                            .map(RecommendationPipeline::toSearchCandidate)
                            .collect(Collectors.toList());
                    badgeAssembler.attach(mapped, condition);
                    return mapped;
                }));
    }

    /**
     * 카카오 검색 결과로 화면에 보여준 후보 하나를 사용자가 실제로 골랐을 때, 그 이름으로
     * TourAPI(KorService2)와 조인해 contentId 등을 채운 온전한 추천 카드로 되돌려준다. 이름매칭에
     * 실패하면 빈 리스트를 돌려준다(프론트가 "관광공사 데이터에 없어 등록할 수 없어요" 안내).
     */
    public Mono<List<RecommendationCandidate>> resolveByName(String regionCode, String placeName,
                                                                String mapX, String mapY) {
        RegionCode region = regionCodeService.find(regionCode)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지역코드: " + regionCode));
        RelatedCandidate raw = RelatedCandidate.builder().placeName(placeName).mapX(mapX).mapY(mapY).build();
        return stage1.resolveByNameCascading(raw, region)
                .flatMap(resolved -> {
                    if (resolved.getContentId() == null) {
                        return Mono.just(List.<RecommendationCandidate>of());
                    }
                    return stage2.filter(List.of(resolved))
                            .flatMap(list -> resolveCondition(region).map(condition -> {
                                List<RecommendationCandidate> mapped = list.stream()
                                        .map(RecommendationPipeline::toSearchCandidate)
                                        .collect(Collectors.toList());
                                badgeAssembler.attach(mapped, condition);
                                return mapped;
                            }));
                });
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
                .estimatedCostPerPerson(c.getEstimatedCostPerPerson())
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
                .ageRangeText(c.getAgeRangeText())
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

    /** 반려동물 인증 후보(pet)를 테마 후보(themed)보다 우선 배치하며 contentId 기준으로 중복 제거한다 */
    static List<RelatedCandidate> mergePetFirst(List<RelatedCandidate> pet, List<RelatedCandidate> themed) {
        Map<String, RelatedCandidate> byId = new LinkedHashMap<>();
        for (RelatedCandidate c : pet) {
            if (c.getContentId() != null) {
                byId.put(c.getContentId(), c);
            }
        }
        for (RelatedCandidate c : themed) {
            if (c.getContentId() != null) {
                byId.putIfAbsent(c.getContentId(), c);
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** origin의 mapX/mapY가 없으면(또는 조회 실패) distanceKm은 null로 남는다 - 프론트에서 뱃지를 숨긴다 */
    private List<RelatedCandidate> attachDistance(List<RelatedCandidate> candidates, TourAttractionDetail origin) {
        for (RelatedCandidate c : candidates) {
            c.setDistanceKm(GeoUtils.distanceKmSafe(origin.getMapX(), origin.getMapY(), c.getMapX(), c.getMapY()));
        }
        return candidates;
    }

    private static final String[] KIDS_INDOOR_KEYWORDS = {
            "키즈카페", "실내놀이", "체험관", "박물관", "미술관", "전시", "과학관", "동물원", "수족관", "키즈"
    };

    /**
     * 트리거 우선회피 정렬. 혼잡 회피(CROWD)는 여유율 높은 순, 비/폭염은 #실내를 앞으로 당긴다.
     * 동반 자녀가 있으면 그 안에서도 아이 동반에 어울리는 실내 장소(체험관·키즈카페·박물관 등)를
     * 한 번 더 앞으로 당긴다 - 폭염철 아이 동반 여행은 실내 중에서도 "아이가 할 게 있는 곳"이 우선.
     * 스마트 동선(힌트 없음)은 여기 타지 않고, 인기 명소를 오전에 두는 쪽은 SmartPlanService가 담당한다.
     */
    private List<RecommendationCandidate> applyAvoidanceOrdering(List<RecommendationCandidate> candidates,
                                                                   RecommendationRequest.AvoidanceHint hint,
                                                                   List<Integer> childAges) {
        if (hint == RecommendationRequest.AvoidanceHint.CROWD) {
            return candidates.stream()
                    .sorted(Comparator.comparing(RecommendationCandidate::getCrowdRate,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .collect(Collectors.toList());
        }
        if (hint == RecommendationRequest.AvoidanceHint.WEATHER
                || hint == RecommendationRequest.AvoidanceHint.HEAT) {
            boolean hasChildren = childAges != null && !childAges.isEmpty();
            return candidates.stream()
                    .sorted(Comparator
                            .comparing((RecommendationCandidate c) ->
                                    c.getMatchedTags() != null && c.getMatchedTags().contains("#실내") ? 0 : 1)
                            .thenComparing(c -> hasChildren && matchesKidsIndoorKeyword(c) ? 0 : 1))
                    .collect(Collectors.toList());
        }
        return candidates;
    }

    private boolean matchesKidsIndoorKeyword(RecommendationCandidate c) {
        String text = (c.getCategory() == null ? "" : c.getCategory())
                + " " + (c.getPlaceName() == null ? "" : c.getPlaceName());
        for (String keyword : KIDS_INDOOR_KEYWORDS) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 예산 필터(1인 기준, 이하만 통과) - Stage1~4에서는 걸러내지 않고 후보 풀을 그대로 유지하다가
     * (유모차 필터가 AccessibilityRanking에서 속성만 참고하고 후보를 안 줄이는 것과 같은 이유 -
     * 앞 단계 랭킹/LLM이 온전한 풀을 보고 판단하게 하기 위함) 최종 목록에서만 실제로 제거한다.
     * estimatedCostPerPerson이 null(정보없음)이면 "비싸다"고 단정하지 않고 통과시킨다.
     */
    private static boolean withinBudget(RecommendationCandidate c, Integer maxBudgetPerPerson) {
        if (maxBudgetPerPerson == null) {
            return true;
        }
        Integer cost = c.getEstimatedCostPerPerson();
        return cost == null || cost <= maxBudgetPerPerson;
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
