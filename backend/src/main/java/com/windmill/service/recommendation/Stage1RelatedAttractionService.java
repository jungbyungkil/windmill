package com.windmill.service.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.client.KakaoLocalSearchClient;
import com.windmill.client.KorServiceClient;
import com.windmill.client.PetFriendlyAttractionClient;
import com.windmill.client.RelatedAttractionClient;
import com.windmill.client.TourApiArrange;
import com.windmill.domain.RecommendThemeTag;
import com.windmill.dto.RegionCode;
import com.windmill.dto.RelatedCandidate;
import com.windmill.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 4단계 검증 로직 - 1단계: 연관 관광지 조회 (TarRlteTarService1).
 * seedPlaceName이 있으면 해당 장소 기준 연관목록(searchKeyword1), 없으면 속초 지역 전체 연관목록(areaBasedList1).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Stage1RelatedAttractionService {

    /** data.go.kr 요청 제한(429) 방지를 위한 후속 단계 외부 API 동시 호출 상한 */
    private static final int EXTERNAL_CALL_CONCURRENCY = 4;
    /** 후속 단계(이름매칭/영업시간/집중률)로 넘길 후보 상한 - 인기(조회순/연관순위) 상위만 사용해 API 쿼터 절약 */
    private static final int MAX_CANDIDATES = 20;
    /** TourAPI contentTypeId 14 = 문화시설(박물관/미술관/전시관 등) - 우천 시 실내 대체 코스 재검색 기준 */
    private static final int INDOOR_CONTENT_TYPE_ID = 14;
    /** 이름 조인 시 인기순 1건만 쓰면 "DDP"가 먼 동명이인/다른 시설로 붙을 수 있어 여러 건을 보고 고른다 */
    private static final int RESOLVE_KEYWORD_ROWS = 10;
    /** 카카오/지도에서 고른 좌표와 TourAPI 후보를 같은 장소로 인정하는 최대 거리 */
    static final double RESOLVE_MAX_DISTANCE_KM = 1.5;

    private final RelatedAttractionClient relatedAttractionClient;
    private final KorServiceClient korServiceClient;
    private final PetFriendlyAttractionClient petFriendlyAttractionClient;
    private final KakaoLocalSearchClient kakaoLocalSearchClient;

    /**
     * 장소명 직접 검색 - 가고 싶은 곳을 이미 알고 있을 때, 연관/추천 로직 없이 그 지역 안에서
     * 이름으로 바로 찾는다("DDP" 검색 → 앵커로 등록). LLM/집중률 필터 없이 KorService2
     * searchKeyword2 결과를 인기(조회)순으로 사용한다.
     *
     * ⚠ 선택한 시/군/구로 좁혀 찾다가 0건이면 시/도 전체 → 전국 순으로 넓혀서 재시도한다(라이브로
     * 실제 확인한 사례: "DDP"는 이름 때문에 동대문구로 착각하기 쉽지만 실제 행정구역은 중구라,
     * 동대문구로 좁힌 검색은 0건이었음 - 서울 전체로 넓히면 정상적으로 찾아짐). 이름 검색은 정확한
     * 장소명을 아는 사용자를 위한 경로라 카테고리 검색과 달리 넓혀도 결과가 산으로 갈 위험이 적다.
     */
    public Mono<List<RelatedCandidate>> searchByName(RegionCode region, String query) {
        if (query == null || query.isBlank()) {
            return Mono.just(List.of());
        }
        String trimmed = query.trim();
        return searchKeywordAsCandidates(trimmed, region.getLDongRegnCd(), region.getLDongSignguCd())
                .flatMap(list -> !list.isEmpty()
                        ? Mono.just(list)
                        : searchKeywordAsCandidates(trimmed, region.getLDongRegnCd(), null))
                .flatMap(list -> !list.isEmpty()
                        ? Mono.just(list)
                        : searchKeywordAsCandidates(trimmed, null, null));
    }

    private Mono<List<RelatedCandidate>> searchKeywordAsCandidates(String keyword, String regnCd, String signguCd) {
        return korServiceClient.searchKeyword(keyword, null, regnCd, signguCd, MAX_CANDIDATES, 1)
                .map(items -> mapKorItems(items, null));
    }

    /**
     * 이름으로 검색 - 카카오맵 검색창과 동일한 결과를 우선 쓴다(2026-08-16 사용자 요청, 기존
     * KorService2 자체 검색보다 훨씬 폭넓고 정확함). "강원도 속초 중앙시장"처럼 지역명을 쿼리 앞에
     * 붙여 카카오맵에서 직접 검색하는 것과 같은 문맥을 준다. 카카오 키가 없거나 결과가 0건이면
     * 기존 TourAPI 캐스케이드 검색(searchByName)으로 폴백해, 검색 기능 전체가 외부 신규 의존성
     * 하나에만 묶이지 않도록 한다. 여기서 나온 카카오 결과는 contentId가 비어있다 - 화면엔 그대로
     * 보여주고, 사용자가 실제로 하나를 고르면 resolveByNameCascading()으로 그때 매칭한다.
     */
    public Mono<List<RelatedCandidate>> searchByNameViaKakao(RegionCode region, String query) {
        if (query == null || query.isBlank()) {
            return Mono.just(List.of());
        }
        String trimmed = query.trim();
        String prefixed = (region.getSidoName() + " " + region.getSignguName() + " " + trimmed).trim();
        return kakaoLocalSearchClient.searchKeyword(prefixed)
                .flatMap(list -> !list.isEmpty() ? Mono.just(list) : searchByName(region, trimmed));
    }

    public Mono<List<RelatedCandidate>> fetch(RegionCode region, String seedPlaceName, boolean withPet) {
        if (withPet) {
            return fetchPetFriendly(region, seedPlaceName);
        }
        // legacy areaCd/signguCd는 LDONG에서 파생됨: areaCd=lDongRegnCd, signguCd=signguFullCode (RegionCodeService 참고)
        String areaCd = region.getLDongRegnCd();
        String signguCd = region.getSignguFullCode();
        Mono<List<JsonNode>> itemsMono = (seedPlaceName == null || seedPlaceName.isBlank())
                ? relatedAttractionClient.areaBasedRelated(areaCd, signguCd, 100, 1)
                : relatedAttractionClient.searchKeywordRelated(areaCd, signguCd, seedPlaceName, 50, 1);

        return itemsMono.map(items -> {
            Map<String, RelatedCandidate> byName = new LinkedHashMap<>();
            for (JsonNode item : items) {
                String name = item.path("rlteTatsNm").asText(null);
                if (name == null || name.isBlank()) {
                    continue;
                }
                int rank = item.path("rlteRank").asInt(999);
                RelatedCandidate existing = byName.get(name);
                if (existing == null || rank < existing.getRank()) {
                    byName.put(name, RelatedCandidate.builder()
                            .placeName(name)
                            .categoryLcls(item.path("rlteCtgryLclsNm").asText(null))
                            .categoryMcls(item.path("rlteCtgryMclsNm").asText(null))
                            .categoryScls(item.path("rlteCtgrySclsNm").asText(null))
                            .rank(rank)
                            .build());
                }
            }
            List<RelatedCandidate> result = takeTopByPopularity(new ArrayList<>(byName.values()));
            log.info("[Stage1] 연관관광지 후보 {}건 확보(상위 {}건로 제한, seed={})", result.size(), MAX_CANDIDATES, seedPlaceName);
            return result;
        });
    }

    /**
     * 반려동물 동반 시 TarRlteTarService1(연관관광지) 대신 전용 KorPetTourService2를 후보 소스로 쓴다.
     * 이 API 응답은 KorService2와 동일 스키마(contentid/title/firstimage)라 resolveContentIds의
     * 별도 이름매칭 조인이 필요 없다 - contentId가 이미 채워진 채로 반환된다.
     * public: RecommendationPipeline이 태그 검색 경로(fetchByThemes)에서도 반려동물 후보를 함께
     * 병합하기 위해 직접 호출한다(2026-08-20 - withPet이 태그 있을 때 완전히 무시되던 버그 수정).
     */
    public Mono<List<RelatedCandidate>> fetchPetFriendly(RegionCode region, String seedPlaceName) {
        Mono<List<JsonNode>> itemsMono = (seedPlaceName == null || seedPlaceName.isBlank())
                ? petFriendlyAttractionClient.areaBasedList(region.getLDongRegnCd(), region.getLDongSignguCd(), MAX_CANDIDATES, 1)
                : petFriendlyAttractionClient.searchKeyword(seedPlaceName, region.getLDongRegnCd(), region.getLDongSignguCd(), MAX_CANDIDATES, 1);

        return itemsMono.map(items -> {
            List<RelatedCandidate> result = new ArrayList<>();
            int rank = 1;
            for (JsonNode item : items) {
                String name = item.path("title").asText(null);
                if (name == null || name.isBlank()) {
                    continue;
                }
                result.add(RelatedCandidate.builder()
                        .placeName(name)
                        .contentId(item.path("contentid").asText(null))
                        .contentTypeId(parseContentTypeId(item))
                        .thumbnailUrl(firstImageUrl(item))
                        .categoryLcls("반려동물동반")
                        .rank(rank++)
                        .build());
            }
            log.info("[Stage1] 반려동물동반 후보 {}건 확보 (seed={})", result.size(), seedPlaceName);
            return result;
        });
    }

    /**
     * 우천 시 실내 대체 코스 - 연관관광지(TarRlteTarService1) 대신 KorService2의 지역기반 목록을
     * 문화시설(contentTypeId=14)로 필터링해 재검색한다. searchKeyword2는 키워드 필수라 "카테고리만으로
     * 재검색"에는 맞지 않아, 지역+카테고리 조합 조회가 가능한 areaBasedList2를 대신 사용한다.
     * 응답에 contentId가 이미 채워져 있어 resolveContentIds(이름매칭 조인)가 필요 없다.
     */
    public Mono<List<RelatedCandidate>> fetchIndoor(RegionCode region) {
        return korServiceClient.areaBasedList(INDOOR_CONTENT_TYPE_ID, region.getLDongRegnCd(), region.getLDongSignguCd(),
                        MAX_CANDIDATES, 1, TourApiArrange.POPULAR)
                .map(items -> mapKorItems(items, "실내"));
    }

    /**
     * 폭염 시 실내 대체 - 문화시설(박물관·전시)로 야외를 바꾼다. 카페·식당은 넣지 않는다.
     */
    public Mono<List<RelatedCandidate>> fetchIndoorForHeat(RegionCode region) {
        return fetchIndoor(region)
                .doOnNext(list -> log.info("[Stage1] 폭염 대체(실내) 후보 {}건 확보", list.size()));
    }

    /**
     * 스마트 동선 오전·오후 스팟 - 그 지역 인기 관광지(12)·문화시설(14)·레포츠(28).
     * 음식점(39)·숙박(32)은 넣지 않는다. 축제는 FestivalTriggerService가 날짜가 겹치는 것만 얹는다.
     */
    public Mono<List<RelatedCandidate>> fetchPopularSights(RegionCode region) {
        Mono<List<RelatedCandidate>> spots = korServiceClient
                .areaBasedList(12, region.getLDongRegnCd(), region.getLDongSignguCd(), MAX_CANDIDATES, 1,
                        TourApiArrange.POPULAR)
                .map(items -> mapKorItems(items, "관광지"))
                .onErrorReturn(List.of());
        Mono<List<RelatedCandidate>> culture = korServiceClient
                .areaBasedList(14, region.getLDongRegnCd(), region.getLDongSignguCd(), MAX_CANDIDATES, 1,
                        TourApiArrange.POPULAR)
                .map(items -> mapKorItems(items, "문화시설"))
                .onErrorReturn(List.of());
        Mono<List<RelatedCandidate>> leports = korServiceClient
                .areaBasedList(28, region.getLDongRegnCd(), region.getLDongSignguCd(), MAX_CANDIDATES, 1,
                        TourApiArrange.POPULAR)
                .map(items -> mapKorItems(items, "레포츠"))
                .onErrorReturn(List.of());
        return Mono.zip(spots, culture, leports)
                .map(tuple -> {
                    Map<String, RelatedCandidate> byId = new LinkedHashMap<>();
                    for (List<RelatedCandidate> batch : List.of(tuple.getT1(), tuple.getT2(), tuple.getT3())) {
                        for (RelatedCandidate c : batch) {
                            if (c.getContentId() == null || isDiningOrLodging(c.getContentTypeId())) {
                                continue;
                            }
                            byId.putIfAbsent(c.getContentId(), c);
                        }
                    }
                    List<RelatedCandidate> result = takeTopByPopularity(new ArrayList<>(byId.values()));
                    log.info("[Stage1] 인기 스팟 후보 {}건 확보", result.size());
                    return result;
                });
    }

    /**
     * UI 해시태그(#자연 #실내 #맛집 #아이동반 #액티비티 #역사) 전용 Stage1.
     * 테마마다 KorService2 contentType + 키워드로 모아 최소 후보를 확보한다.
     */
    public Mono<List<RelatedCandidate>> fetchByThemes(RegionCode region, List<String> tags, String query) {
        List<RecommendThemeTag> themes = RecommendThemeTag.resolve(tags, query);
        if (themes.isEmpty()) {
            return Mono.just(List.of());
        }
        // 맛집만이면 기존 카페 필터 로직 재사용
        if (themes.size() == 1 && themes.get(0) == RecommendThemeTag.FOOD) {
            return fetchFood(region, query);
        }
        // 테마별 조회를 병렬로 돌린다(concatMap→flatMapSequential) - 순서는 그대로 보존하면서
        // 실측상 테마 수만큼(최대 3개) 순차 대기하던 걸 동시 대기로 줄인다(2026-08-16 "당일치기
        // 시작하기" 속도 개선 - 표준 4단계 일정 생성 26초 중 Stage1이 약 2.3초를 차지했음).
        return Flux.fromIterable(themes)
                .flatMapSequential(theme -> fetchOneTheme(region, theme, query), Math.max(themes.size(), 1))
                .collectList()
                .map(batches -> {
                    Map<String, RelatedCandidate> byId = new LinkedHashMap<>();
                    for (List<RelatedCandidate> batch : batches) {
                        for (RelatedCandidate c : batch) {
                            if (c.getContentId() != null) {
                                byId.putIfAbsent(c.getContentId(), c);
                            }
                        }
                    }
                    List<RelatedCandidate> result = takeTopByPopularity(new ArrayList<>(byId.values()));
                    log.info("[Stage1] 테마 태그 후보 {}건 확보 (themes={})", result.size(),
                            themes.stream().map(RecommendThemeTag::getTag).toList());
                    return result;
                });
    }

    /** 한 테마의 여러 조회를 동시에 던지는 상한 - 예전엔 concatMap으로 6~9개를 순차 대기해 검색이 수 초씩
     *  걸렸다(2026-09-06 사용자 제보). 순서는 뒤에서 contentId 중복제거 + 인기(rank)순 재정렬로 정하므로
     *  호출 간 순서를 보존할 이유가 없어 병렬로 바꾼다. */
    private static final int THEME_FETCH_CONCURRENCY = 8;

    private Mono<List<RelatedCandidate>> fetchOneTheme(RegionCode region, RecommendThemeTag theme, String query) {
        List<Mono<List<RelatedCandidate>>> calls = new ArrayList<>();
        RecommendThemeTag.CategoryCode[] categoryCodes = theme.getCategoryCodes();
        Integer primaryType = theme.getContentTypeIds().length > 0 ? theme.getContentTypeIds()[0] : null;
        boolean hasCategoryCodes = categoryCodes.length > 0;
        if (hasCategoryCodes) {
            // 분류코드(cat1/cat2/cat3)가 있는 태그는 정밀 조회를 우선 사용 - "사찰"/"온천" 같은 카테고리는
            // 실제 상호명에 그 단어가 안 들어가 키워드 검색만으론 정확도가 낮다.
            for (RecommendThemeTag.CategoryCode code : categoryCodes) {
                calls.add(korServiceClient
                        .areaBasedList(primaryType, code.cat1(), code.cat2(), code.cat3(),
                                region.getLDongRegnCd(), region.getLDongSignguCd(), MAX_CANDIDATES, 1,
                                TourApiArrange.POPULAR)
                        .map(items -> mapKorItems(items, theme.getLabel()))
                        .onErrorReturn(List.of()));
            }
        } else {
            for (int typeId : theme.getContentTypeIds()) {
                calls.add(korServiceClient
                        .areaBasedList(typeId, region.getLDongRegnCd(), region.getLDongSignguCd(), MAX_CANDIDATES, 1,
                                TourApiArrange.POPULAR)
                        .map(items -> mapKorItems(items, theme.getLabel()))
                        .onErrorReturn(List.of()));
            }
        }
        // 키워드 검색은 보조용 - 분류코드가 이미 정밀하면 1개, 타입만으로 조회하는 태그면 2개까지만.
        // (예전엔 word 3개 × [타입지정·타입없음] 2 = 6콜을 전부 던졌음)
        List<String> searchWords = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            searchWords.add(query.trim());
        }
        int keywordLimit = hasCategoryCodes ? 1 : 2;
        for (String keyword : theme.getKeywords()) {
            if (searchWords.size() >= keywordLimit) {
                break;
            }
            if (!searchWords.contains(keyword)) {
                searchWords.add(keyword);
            }
        }
        for (int i = 0; i < searchWords.size(); i++) {
            String word = searchWords.get(i);
            calls.add(korServiceClient
                    .searchKeyword(word, primaryType, region.getLDongRegnCd(), region.getLDongSignguCd(), MAX_CANDIDATES, 1)
                    .map(items -> mapKorItems(items, theme.getLabel()))
                    .onErrorReturn(List.of()));
            // contentType 없이 한 번 더 - 첫 단어만(지역 타입이 통째로 비는 경우 대비, 나머지는 생략해 콜 수 절감)
            if (i == 0) {
                calls.add(korServiceClient
                        .searchKeyword(word, null, region.getLDongRegnCd(), region.getLDongSignguCd(), MAX_CANDIDATES, 1)
                        .map(items -> mapKorItems(items, theme.getLabel()))
                        .onErrorReturn(List.of()));
            }
        }

        return Flux.fromIterable(calls)
                .flatMap(mono -> mono, THEME_FETCH_CONCURRENCY)
                .collectList()
                .map(batches -> {
                    Map<String, RelatedCandidate> byId = new LinkedHashMap<>();
                    for (List<RelatedCandidate> batch : batches) {
                        for (RelatedCandidate c : batch) {
                            if (c.getContentId() == null || c.getPlaceName() == null) {
                                continue;
                            }
                            if (!theme.isDining() && isDiningOrLodging(c.getContentTypeId())) {
                                continue;
                            }
                            if (theme == RecommendThemeTag.FOOD
                                    && containsAny(c.getPlaceName(), "카페", "커피", "디저트", "베이커리")) {
                                continue;
                            }
                            byId.putIfAbsent(c.getContentId(), c);
                        }
                    }
                    // 카페 필터로 맛집이 비면 필터 해제
                    if (theme == RecommendThemeTag.FOOD && byId.isEmpty()) {
                        for (List<RelatedCandidate> batch : batches) {
                            for (RelatedCandidate c : batch) {
                                if (c.getContentId() != null) {
                                    byId.putIfAbsent(c.getContentId(), c);
                                }
                            }
                        }
                    }
                    List<RelatedCandidate> result = new ArrayList<>(byId.values());
                    log.info("[Stage1] 테마 {} 후보 {}건", theme.getTag(), result.size());
                    return result;
                });
    }

    /**
     * 식당/맛집 추천 - 연관관광지(TarRlteTarService1)에는 음식점이 거의 없어
     * KorService2 음식점(contentTypeId=39) 지역목록 + 키워드 검색으로 후보를 모은다.
     */
    public Mono<List<RelatedCandidate>> fetchFood(RegionCode region, String query) {
        String keyword = (query == null || query.isBlank()) ? "맛집" : query.trim();
        boolean preferRestaurant = !containsAny(keyword, "카페", "커피", "디저트");

        Mono<List<RelatedCandidate>> byType = korServiceClient
                .areaBasedList(39, region.getLDongRegnCd(), region.getLDongSignguCd(), MAX_CANDIDATES, 1,
                        TourApiArrange.POPULAR)
                .map(items -> mapKorItems(items, "맛집"))
                .onErrorReturn(List.of());

        Mono<List<RelatedCandidate>> byKeyword = korServiceClient
                .searchKeyword(keyword, 39, region.getLDongRegnCd(), region.getLDongSignguCd(), MAX_CANDIDATES, 1)
                .map(items -> mapKorItems(items, "맛집"))
                .onErrorReturn(List.of());

        // 보조: contentType 없이 키워드만 (지역 음식점 타입이 비는 경우)
        Mono<List<RelatedCandidate>> byKeywordAny = preferRestaurant
                ? korServiceClient
                    .searchKeyword(keyword, null, region.getLDongRegnCd(), region.getLDongSignguCd(), MAX_CANDIDATES, 1)
                    .map(items -> mapKorItems(items, "맛집"))
                    .onErrorReturn(List.of())
                : Mono.just(List.of());

        return Mono.zip(byType, byKeyword, byKeywordAny)
                .map(tuple -> {
                    Map<String, RelatedCandidate> byId = new LinkedHashMap<>();
                    List<RelatedCandidate> all = new ArrayList<>();
                    all.addAll(tuple.getT1());
                    all.addAll(tuple.getT2());
                    all.addAll(tuple.getT3());
                    for (RelatedCandidate c : all) {
                        putFoodCandidate(byId, c, preferRestaurant);
                    }
                    // 카페만 걸러 결과가 비면 음식점 타입(39) 전체를 다시 채운다
                    if (byId.isEmpty()) {
                        for (RelatedCandidate c : all) {
                            putFoodCandidate(byId, c, false);
                        }
                    }
                    List<RelatedCandidate> result = takeTopByPopularity(new ArrayList<>(byId.values()));
                    log.info("[Stage1] 맛집/식당 후보 {}건 확보 (keyword={})", result.size(), keyword);
                    return result;
                });
    }

    private void putFoodCandidate(Map<String, RelatedCandidate> byId, RelatedCandidate c, boolean preferRestaurant) {
        if (c.getContentId() == null || c.getPlaceName() == null) {
            return;
        }
        if (preferRestaurant && containsAny(c.getPlaceName(), "카페", "커피", "디저트", "베이커리")) {
            // 순수 카페는 식당 검색에서 뒤로 밀되, 결과가 비면 아래에서 다시 허용할 수 있게 일단 제외
            return;
        }
        byId.putIfAbsent(c.getContentId(), c);
    }

    private static boolean containsAny(String text, String... keywords) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** TourAPI 조회순(rank) 상위 MAX_CANDIDATES만 남긴다. skipLlm 속도 캡이 이 순서를 그대로 잘라 쓴다. */
    private static List<RelatedCandidate> takeTopByPopularity(List<RelatedCandidate> result) {
        result.sort(PopularityRanking.relatedComparator());
        if (result.size() > MAX_CANDIDATES) {
            return new ArrayList<>(result.subList(0, MAX_CANDIDATES));
        }
        return result;
    }

    private List<RelatedCandidate> mapKorItems(List<JsonNode> items, String categoryLcls) {
        List<RelatedCandidate> result = new ArrayList<>();
        int rank = 1;
        for (JsonNode item : items) {
            String name = item.path("title").asText(null);
            if (name == null || name.isBlank() || isUnsuitableForCasualTrip(name)) {
                continue;
            }
            String mapX = blankToNull(item.path("mapx").asText(null));
            String mapY = blankToNull(item.path("mapy").asText(null));
            result.add(RelatedCandidate.builder()
                    .placeName(name)
                    .contentId(item.path("contentid").asText(null))
                    .contentTypeId(parseContentTypeId(item))
                    .thumbnailUrl(firstImageUrl(item))
                    .mapX(mapX)
                    .mapY(mapY)
                    .categoryLcls(categoryLcls)
                    .rank(rank++)
                    .build());
        }
        log.info("[Stage1] {} 후보 {}건 확보", categoryLcls, result.size());
        return result;
    }

    /**
     * 회원제·예약제라 당일치기 추천에 부적합한 시설을 이름으로 걸러낸다(2026-09-13 사용자 제보 -
     * 4인 가족 표준 일정에 골프장 "설악프라자컨트리클럽"이 추천됨). TourAPI 카테고리 분류가
     * 부정확한 경우(골프장이 문화시설/레포츠 등으로 잘못 등록)에도 걸러지도록 contentTypeId·cat3가
     * 아니라 이름 키워드로 판단한다 - 이 메서드는 모든 KorService2 목록 조회가 공유하는 유일한
     * 매핑 지점이라 여기 한 곳만 고치면 스마트 동선·검색·대안 추천 전부에 적용된다.
     */
    private static boolean isUnsuitableForCasualTrip(String placeName) {
        String name = placeName.toLowerCase(Locale.ROOT);
        return name.contains("골프장") || name.contains("골프클럽") || name.contains("컨트리클럽");
    }

    private Integer parseContentTypeId(JsonNode item) {
        String typeId = item.path("contenttypeid").asText(null);
        return typeId == null || typeId.isBlank() ? null : Integer.valueOf(typeId);
    }

    /** TourAPI 39 음식점, 32 숙박 - 오전·오후 관광 슬롯에 넣지 않는다. */
    static boolean isDiningOrLodging(Integer contentTypeId) {
        return contentTypeId != null && (contentTypeId == 39 || contentTypeId == 32);
    }

    /**
     * 연관관광지 API는 KorService2의 contentId와 무관한 자체 코드만 제공하므로,
     * 관광지명(placeName) 기준 키워드검색(searchKeyword2)으로 조인해 contentId/contentTypeId를 채운다.
     * 매칭 실패(동명이인/표기 차이 등)한 후보는 제외하고 로그만 남긴다.
     */
    public Mono<List<RelatedCandidate>> resolveContentIds(List<RelatedCandidate> candidates, RegionCode region) {
        return Flux.fromIterable(candidates)
                .flatMap(c -> resolveOne(c, region.getLDongRegnCd(), region.getLDongSignguCd()), EXTERNAL_CALL_CONCURRENCY)
                .filter(c -> c.getContentId() != null)
                .collectList()
                .doOnNext(list -> log.info("[Stage1] KorService2 이름매칭 성공 {}건 / {}건 중", list.size(), candidates.size()));
    }

    /**
     * 카카오 로컬 검색처럼 TourAPI 밖에서 고른 단일 후보를 이름으로 KorService2와 조인한다.
     * resolveContentIds(다건 병렬 조인)와 같은 이름매칭 로직이지만, 사용자가 이미 정확한 이름을
     * 골라 확정한 단일 후보라 시/군/구→시/도→전국 순으로 넓혀가며 재시도한다(searchByName과 동일한
     * 완화 전략 - "DDP"처럼 좁은 범위에서 0건이어도 넓히면 찾아지는 사례가 실제로 있었음).
     */
    public Mono<RelatedCandidate> resolveByNameCascading(RelatedCandidate candidate, RegionCode region) {
        return resolveOne(candidate, region.getLDongRegnCd(), region.getLDongSignguCd())
                .flatMap(r -> r.getContentId() != null ? Mono.just(r) : resolveOne(candidate, region.getLDongRegnCd(), null))
                .flatMap(r -> r.getContentId() != null ? Mono.just(r) : resolveOne(candidate, null, null));
    }

    private Mono<RelatedCandidate> resolveOne(RelatedCandidate candidate, String regnCd, String signguCd) {
        if (candidate.getContentId() != null) {
            // 반려동물동반 소스(fetchPetFriendly)는 이미 contentId가 채워져 있어 재조회 불필요
            return Mono.just(candidate);
        }
        return korServiceClient.searchKeyword(candidate.getPlaceName(), null, regnCd, signguCd,
                        RESOLVE_KEYWORD_ROWS, 1)
                .map(items -> applyKorMatch(candidate, items))
                .defaultIfEmpty(candidate)
                .onErrorReturn(candidate);
    }

    private RelatedCandidate applyKorMatch(RelatedCandidate candidate, List<JsonNode> items) {
        if (items == null || items.isEmpty()) {
            log.warn("[Stage1] '{}' KorService2 이름매칭 실패 - 후보에서 제외", candidate.getPlaceName());
            return candidate;
        }
        JsonNode match = pickKorMatch(candidate, items);
        if (match == null) {
            log.warn("[Stage1] '{}' KorService2 후보 {}건 중 좌표/이름 불일치 - 후보에서 제외",
                    candidate.getPlaceName(), items.size());
            return candidate;
        }
        String pickedName = candidate.getPlaceName();
        candidate.setContentId(match.path("contentid").asText(null));
        String typeId = match.path("contenttypeid").asText(null);
        candidate.setContentTypeId(typeId == null ? null : Integer.valueOf(typeId));
        candidate.setThumbnailUrl(firstImageUrl(match));
        if (blankToNull(candidate.getMapX()) == null) {
            candidate.setMapX(blankToNull(match.path("mapx").asText(null)));
        }
        if (blankToNull(candidate.getMapY()) == null) {
            candidate.setMapY(blankToNull(match.path("mapy").asText(null)));
        }
        String official = blankToNull(match.path("title").asText(null));
        if (official != null) {
            if (!official.equals(pickedName)) {
                log.info("[Stage1] '{}' → '{}' contentId={} 로 확정",
                        pickedName, official, candidate.getContentId());
            }
            candidate.setPlaceName(official);
        }
        return candidate;
    }

    /**
     * 인기순 1등 대신, 고른 좌표에 가깝고 이름이 맞는 후보를 고른다.
     * 좌표가 있는데 모두 멀면 null(잘못된 첫 건을 조용히 붙이지 않음).
     * 좌표가 없으면 이름 점수가 있는 후보를 우선하고, 없으면 기존처럼 1등을 쓴다.
     */
    static JsonNode pickKorMatch(RelatedCandidate candidate, List<JsonNode> items) {
        boolean hasOrigin = blankToNull(candidate.getMapX()) != null && blankToNull(candidate.getMapY()) != null;
        JsonNode best = null;
        int bestNameScore = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (JsonNode item : items) {
            int nameScore = nameScore(candidate.getPlaceName(), item.path("title").asText(""));
            Double dist = null;
            if (hasOrigin) {
                dist = GeoUtils.distanceKmSafe(
                        candidate.getMapX(), candidate.getMapY(),
                        blankToNull(item.path("mapx").asText(null)),
                        blankToNull(item.path("mapy").asText(null)));
                if (dist == null || dist > RESOLVE_MAX_DISTANCE_KM) {
                    continue;
                }
            } else if (nameScore <= 0) {
                continue;
            }
            double distance = dist == null ? Double.POSITIVE_INFINITY : dist;
            if (best == null || nameScore > bestNameScore
                    || (nameScore == bestNameScore && distance < bestDistance)) {
                best = item;
                bestNameScore = nameScore;
                bestDistance = distance;
            }
        }
        if (best != null) {
            return best;
        }
        if (!hasOrigin && !items.isEmpty()) {
            return items.get(0);
        }
        return null;
    }

    static int nameScore(String query, String title) {
        String q = normalizePlaceName(query);
        String t = normalizePlaceName(title);
        if (q.isEmpty() || t.isEmpty()) {
            return 0;
        }
        if (q.equals(t) || q.contains(t) || t.contains(q)) {
            return 2;
        }
        return 0;
    }

    static String normalizePlaceName(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("[\\s()\\[\\]{}·.,/\\-]", "").toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** firstimage 우선, 없으면 firstimage2 (KorService2·연관관광지 공통 스키마). 둘 다 없으면 null. */
    private static String firstImageUrl(JsonNode node) {
        String primary = node.path("firstimage").asText(null);
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        String secondary = node.path("firstimage2").asText(null);
        return secondary == null || secondary.isBlank() ? null : secondary;
    }
}
