package com.windmill.service.recommendation;

import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RecommendationRequest;
import com.windmill.dto.RegionCode;
import com.windmill.dto.RelatedCandidate;
import com.windmill.dto.TourAttractionDetail;
import com.windmill.domain.CongestionSensitivity;
import com.windmill.domain.RainSensitivity;
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
                    boolean routeOrBusinessAlternative =
                            request.getAvoidanceHint() == RecommendationRequest.AvoidanceHint.BUSINESS
                                    || request.getAvoidanceHint() == RecommendationRequest.AvoidanceHint.ROUTE;
                    if (heatAlternative) {
                        stage1Result = stage1.fetchIndoorForHeat(region);
                    } else if (rainAlternative) {
                        stage1Result = stage1.fetchIndoor(region);
                    } else if (routeOrBusinessAlternative) {
                        // 휴무/마감/이동시간 대안: 연관관광지+이름매칭 조인(느리고 소규모 지역에선 잘 빔) 대신
                        // contentId가 이미 채워진 지역 인기 스팟(관광지12·문화시설14·레포츠28)을 바로 쓴다.
                        // "휴무 아닌 곳 몇 군데"만 있으면 되는 요청이라 이 소스가 빠르고 대도시에서 안 빈다.
                        stage1Result = stage1.fetchPopularSights(region);
                    } else if (request.isPopularSights()) {
                        Mono<List<RelatedCandidate>> sights = stage1.fetchPopularSights(region);
                        stage1Result = request.isWithPet()
                                ? Mono.zip(stage1.fetchPetFriendly(region, seed), sights)
                                        .map(petTuple -> mergePetFirst(
                                                dropDining(petTuple.getT1()), petTuple.getT2()))
                                : sights;
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
                            .flatMap(list -> stage2.filter(list, request.getVisitDate()))
                            .map(list -> attachDistance(list, origin))
                            .flatMap(list -> stage3.filter(list, region))
                            .map(list -> CompanionCategoryRanking.rank(list, request.getCompanionType()))
                            .map(list -> AccessibilityRanking.rank(list, request.isStrollerFriendly(), request.isAccessibleFriendly()))
                            .map(list -> AgeGroupRanking.rank(list, request.getAdultAgeGroup(), request.getChildAges()))
                            .map(ReservationRequiredRanking::rank)
                            .map(ProximityRanking::rank)
                            .map(PopularityRanking::rank)
                            .flatMap(list -> request.isSkipLlm()
                                    ? stage4.matchWithoutLlm(list, request.getTags(), request.getChildAges())
                                    : stage4.match(list, request.getTags(), request.getNaturalLanguageQuery(), request.getChildAges()))
                            .map(list -> enrichThemeTags(list, themes))
                            .doOnNext(list -> badgeAssembler.attach(list, condition, request.getVisitDate()));
                })
                .map(list -> list.stream()
                        .filter(c -> !excludeNames.contains(c.getPlaceName()))
                        .filter(c -> withinBudget(c, request.getMaxBudgetPerPerson()))
                        .collect(Collectors.toList()))
                .map(list -> applyAvoidanceOrdering(list, request.getAvoidanceHint(), request.getChildAges()))
                .flatMap(list -> !list.isEmpty() || request.getAvoidanceHint() == null
                        ? Mono.just(list)
                        : alternativesFallback(request))
                .onErrorResume(e -> {
                    // 트리거 대안은 파이프라인이 도중에 터져도(외부 API 오류 등) 빈손으로 끝나지 않는다.
                    if (request.getAvoidanceHint() == null) {
                        return Mono.error(e);
                    }
                    log.warn("[Pipeline] 대안 파이프라인 오류 - 인기 스팟 폴백", e);
                    return Mono.defer(() -> alternativesFallback(request))
                            .onErrorReturn(List.of());
                })
                .doOnNext(list -> log.info("[Pipeline] 최종 추천 {}건", list.size()));
    }

    /**
     * 트리거 대응 대안(avoidanceHint != null)은 <b>절대 빈 목록으로 끝나지 않는다.</b> 연관/실내 경로가
     * 0건이면 그 지역 인기 관광지(관광지 12·문화시설 14·레포츠 28)를 같은 4단계로 보강해 채우고,
     * 그래도 0건이면(이미 담긴 곳 제외로 전부 빠진 경우) exclude 필터를 풀고, 최후에는 인기목록
     * 원본이라도 돌려준다. 순서는 여전히 applyAvoidanceOrdering가 "제일 좋은 것부터"로 잡는다.
     */
    private Mono<List<RecommendationCandidate>> alternativesFallback(RecommendationRequest request) {
        RegionCode region = regionCodeService.find(request.getRegionCode())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 지역코드: " + request.getRegionCode()));
        Set<String> exclude = request.getExcludeContentIds() == null
                ? Set.of() : Set.copyOf(request.getExcludeContentIds());
        Set<String> excludeNames = request.getExcludePlaceNames() == null
                ? Set.of() : Set.copyOf(request.getExcludePlaceNames());
        log.warn("[Pipeline] 대안 0건 - 인기 스팟 폴백 시작 (avoid={}, region={})",
                request.getAvoidanceHint(), request.getRegionCode());

        return Mono.zip(resolveOrigin(request), resolveCondition(region))
                .flatMap(tuple -> {
                    TourAttractionDetail origin = tuple.getT1();
                    RegionCondition condition = tuple.getT2();
                    return stage1.fetchPopularSights(region)
                            .map(list -> capForSpeed(list.stream()
                                    .filter(c -> !exclude.contains(c.getContentId()))
                                    .collect(Collectors.toList())))
                            .flatMap(list -> stage2.filter(list, request.getVisitDate()))
                            .map(list -> attachDistance(list, origin))
                            .flatMap(list -> stage3.filter(list, region))
                            .map(list -> CompanionCategoryRanking.rank(list, request.getCompanionType()))
                            .map(list -> AccessibilityRanking.rank(list, request.isStrollerFriendly(), request.isAccessibleFriendly()))
                            .map(list -> AgeGroupRanking.rank(list, request.getAdultAgeGroup(), request.getChildAges()))
                            .map(ProximityRanking::rank)
                            .map(PopularityRanking::rank)
                            .flatMap(list -> stage4.matchWithoutLlm(list, request.getTags(), request.getChildAges()))
                            .doOnNext(list -> badgeAssembler.attach(list, condition, request.getVisitDate()))
                            .map(list -> list.stream()
                                    .filter(c -> !excludeNames.contains(c.getPlaceName()))
                                    .collect(Collectors.toList()))
                            .map(list -> applyAvoidanceOrdering(list, request.getAvoidanceHint(), request.getChildAges()));
                })
                .flatMap(list -> list.isEmpty() ? rawPopularSpots(region, request) : Mono.just(list))
                .onErrorResume(e -> {
                    log.warn("[Pipeline] 폴백 보강 실패 - 인기목록 원본으로 최후 시도", e);
                    return rawPopularSpots(region, request);
                });
    }

    /** 최후 폴백 - Stage2·3 보강 없이 인기목록 원본이라도 대안으로 내보낸다(TourAPI 자체가 0건일 때만 빈 목록). */
    private Mono<List<RecommendationCandidate>> rawPopularSpots(RegionCode region, RecommendationRequest request) {
        Set<String> exclude = request.getExcludeContentIds() == null
                ? Set.of() : Set.copyOf(request.getExcludeContentIds());
        return stage1.fetchPopularSights(region)
                .map(list -> {
                    List<RecommendationCandidate> mapped = list.stream()
                            .map(RecommendationPipeline::toSearchCandidate)
                            .collect(Collectors.toList());
                    List<RecommendationCandidate> filtered = mapped.stream()
                            .filter(c -> !exclude.contains(c.getContentId()))
                            .collect(Collectors.toList());
                    List<RecommendationCandidate> chosen = filtered.isEmpty() ? mapped : filtered;
                    return applyAvoidanceOrdering(chosen, request.getAvoidanceHint(), request.getChildAges());
                })
                .doOnNext(list -> log.warn("[Pipeline] 최후 폴백 - 인기목록 원본 {}건", list.size()));
    }

    /** skipLlm(속도 우선) 요청 전용 - Stage2(영업시간 상세조회, 외부 API) 대상 건수를 줄여 속도를
     *  확보한다(2026-08-16 실측: 후보 20건 기준 Stage2가 약 16초 - 표준 4단계 일정은 어차피 이 중
     *  1곳만 선택하므로 상위 후보만 미리 조회해도 충분함). Stage1이 이미 인기(조회)순으로 정렬해 반환한다. */
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
                .overview(c.getOverview())
                .detailFacts(c.getDetailFacts())
                .cat3(c.getCat3())
                .indoor(c.getIndoor())
                .rainSensitivity(c.getRainSensitivity())
                .congestionSensitivity(c.getCongestionSensitivity())
                .inferredSource(c.getInferredSource())
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

    private static List<RelatedCandidate> dropDining(List<RelatedCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(c -> !Stage1RelatedAttractionService.isDiningOrLodging(c.getContentTypeId()))
                .collect(Collectors.toList());
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
     * 트리거 우선회피 정렬. 스마트 동선(힌트 없음)은 여기 타지 않고, 인기 명소를 오전에 두는 쪽은
     * SmartPlanService가 담당한다.
     *
     * 인기순 최우선(2026-09-11 사용자 결정 - "혼잡 회피여도 인기순 우선이 맞다, 사용자가 판단하게
     * 해달라"): 혼잡 둔감·우천 둔감·실내 여부 같은 건 "그래도 갈 수는 있는" 소프트 선호라 인기순보다
     * 뒤로 민다 - 실제로 가장 좋은 곳이 뭔지 사용자가 직접 보고 판단하게 한다. 다만 방문일 정기휴무
     * (isVisitableOnDay)는 예외 - "휴무라서 대안을 찾는" 요청에 휴무인 곳을 1순위로 보여주면 아예
     * 갈 수 없는 곳이라 선호 문제가 아니라 무효한 답이라, 이것만 인기순보다 먼저 걸러둔다.
     */
    static List<RecommendationCandidate> applyAvoidanceOrdering(List<RecommendationCandidate> candidates,
                                                                   RecommendationRequest.AvoidanceHint hint,
                                                                   List<Integer> childAges) {
        if (hint == RecommendationRequest.AvoidanceHint.CROWD) {
            // ①지역 인기(조회)순 ②혼잡 둔감 ③실시간 집중률 낮은 순 ④썸네일 있는 카드.
            return candidates.stream()
                    .sorted(Comparator
                            .comparingInt((RecommendationCandidate c) -> PopularityRanking.rankKey(c.getRank()))
                            .thenComparing((RecommendationCandidate c) ->
                                    c.getCongestionSensitivity() == CongestionSensitivity.INSENSITIVE ? 0 : 1)
                            .thenComparing(RecommendationCandidate::getCrowdRate,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(c -> hasThumbnail(c) ? 0 : 1))
                    .collect(Collectors.toList());
        }
        if (hint == RecommendationRequest.AvoidanceHint.ROUTE
                || hint == RecommendationRequest.AvoidanceHint.BUSINESS) {
            // ①방문일에 문 여는 곳(정기휴무 아님, 무효한 답 배제) ②지역 인기순 ③가까운 곳.
            return candidates.stream()
                    .sorted(Comparator
                            .comparingInt((RecommendationCandidate c) -> isVisitableOnDay(c) ? 0 : 1)
                            .thenComparingInt(c -> PopularityRanking.rankKey(c.getRank()))
                            .thenComparing(RecommendationCandidate::getDistanceKm,
                                    Comparator.nullsLast(Comparator.naturalOrder())))
                    .collect(Collectors.toList());
        }
        if (hint == RecommendationRequest.AvoidanceHint.WEATHER
                || hint == RecommendationRequest.AvoidanceHint.HEAT) {
            // ①지역 인기순 ②실내 ③우천 둔감 ④(자녀 동반 시)아이가 즐길 실내 ⑤썸네일.
            boolean hasChildren = childAges != null && !childAges.isEmpty();
            return candidates.stream()
                    .sorted(Comparator
                            .comparingInt((RecommendationCandidate c) -> PopularityRanking.rankKey(c.getRank()))
                            .thenComparing((RecommendationCandidate c) -> indoorPreferred(c) ? 0 : 1)
                            .thenComparing(c -> c.getRainSensitivity() == RainSensitivity.INSENSITIVE ? 0 : 1)
                            .thenComparing(c -> hasChildren && matchesKidsIndoorKeyword(c) ? 0 : 1)
                            .thenComparing(c -> hasThumbnail(c) ? 0 : 1))
                    .collect(Collectors.toList());
        }
        return candidates;
    }

    private static boolean hasThumbnail(RecommendationCandidate c) {
        return c.getThumbnailUrl() != null && !c.getThumbnailUrl().isBlank();
    }

    /** 방문일 기준 정기휴무가 아닌지(Stage2가 채운 businessStatus). 모르면 방문 가능으로 본다. */
    private static boolean isVisitableOnDay(RecommendationCandidate c) {
        return c.getBusinessStatus() != com.windmill.dto.BusinessStatus.CLOSED_DAY;
    }

    /** 실내 스냅샷이 있으면 그걸 쓰고, 없으면 Stage4가 붙인 #실내 태그를 본다. */
    private static boolean indoorPreferred(RecommendationCandidate c) {
        if (Boolean.TRUE.equals(c.getIndoor())) {
            return true;
        }
        return c.getMatchedTags() != null && c.getMatchedTags().contains("#실내");
    }

    private static boolean matchesKidsIndoorKeyword(RecommendationCandidate c) {
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
     * 식당·카페 참고 금액 필터(1인 기준, 이하만 통과). 관광지·체험은 걸러내지 않는다.
     * Stage1~4에서는 후보 풀을 유지하다가 최종 목록에서만 제거한다.
     * estimatedCostPerPerson이 null이면 "비싸다"고 단정하지 않고 통과시킨다.
     */
    private static boolean withinBudget(RecommendationCandidate c, Integer maxBudgetPerPerson) {
        if (maxBudgetPerPerson == null || !isFoodCandidate(c)) {
            return true;
        }
        Integer cost = c.getEstimatedCostPerPerson();
        return cost == null || cost <= maxBudgetPerPerson;
    }

    private static boolean isFoodCandidate(RecommendationCandidate c) {
        if (c.getContentTypeId() != null && c.getContentTypeId() == 39) {
            return true;
        }
        List<String> tags = c.getMatchedTags();
        if (tags == null || tags.isEmpty()) {
            return false;
        }
        return tags.stream().anyMatch(t -> t != null && (
                t.equals("#맛집") || t.equals("#카페") || t.equals("#한식")
                        || t.equals("#중식") || t.equals("#일식") || t.equals("#양식")));
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
