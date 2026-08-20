package com.windmill.service.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.client.KakaoLocalSearchClient;
import com.windmill.client.KorServiceClient;
import com.windmill.client.PetFriendlyAttractionClient;
import com.windmill.client.RelatedAttractionClient;
import com.windmill.domain.RecommendThemeTag;
import com.windmill.dto.RegionCode;
import com.windmill.dto.RelatedCandidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
    /** 후속 단계(이름매칭/영업시간/집중률)로 넘길 후보 상한 - 연관순위(rank) 기준 상위만 사용해 API 쿼터 절약 */
    private static final int MAX_CANDIDATES = 20;
    /** TourAPI contentTypeId 14 = 문화시설(박물관/미술관/전시관 등) - 우천 시 실내 대체 코스 재검색 기준 */
    private static final int INDOOR_CONTENT_TYPE_ID = 14;

    private final RelatedAttractionClient relatedAttractionClient;
    private final KorServiceClient korServiceClient;
    private final PetFriendlyAttractionClient petFriendlyAttractionClient;
    private final KakaoLocalSearchClient kakaoLocalSearchClient;

    /**
     * 장소명 직접 검색 - 가고 싶은 곳을 이미 알고 있을 때, 연관/추천 로직 없이 그 지역 안에서
     * 이름으로 바로 찾는다("DDP" 검색 → 앵커로 등록). LLM/집중률 필터 없이 KorService2
     * searchKeyword2 결과를 이름 관련도 순 그대로 사용한다.
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
            List<RelatedCandidate> result = new ArrayList<>(byName.values());
            result.sort(Comparator.comparingInt(RelatedCandidate::getRank));
            if (result.size() > MAX_CANDIDATES) {
                result = new ArrayList<>(result.subList(0, MAX_CANDIDATES));
            }
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
                String thumbnail = item.path("firstimage").asText(null);
                result.add(RelatedCandidate.builder()
                        .placeName(name)
                        .contentId(item.path("contentid").asText(null))
                        .contentTypeId(parseContentTypeId(item))
                        .thumbnailUrl(thumbnail == null || thumbnail.isBlank() ? null : thumbnail)
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
                        MAX_CANDIDATES, 1, "C")
                .map(items -> mapKorItems(items, "실내"));
    }

    /**
     * 폭염 시 실내 대체 - 문화시설 + 카페를 함께 모아 야외 일정을 대체할 실내 후보를 넓힌다.
     */
    public Mono<List<RelatedCandidate>> fetchIndoorForHeat(RegionCode region) {
        Mono<List<RelatedCandidate>> culture = fetchIndoor(region);
        Mono<List<RelatedCandidate>> cafes = korServiceClient
                .searchKeyword("카페", 39, region.getLDongRegnCd(), region.getLDongSignguCd(), MAX_CANDIDATES, 1)
                .map(items -> mapKorItems(items, "실내"));
        return Mono.zip(culture, cafes)
                .map(tuple -> {
                    Map<String, RelatedCandidate> byId = new LinkedHashMap<>();
                    for (RelatedCandidate c : tuple.getT1()) {
                        if (c.getContentId() != null) {
                            byId.put(c.getContentId(), c);
                        }
                    }
                    for (RelatedCandidate c : tuple.getT2()) {
                        if (c.getContentId() != null) {
                            byId.putIfAbsent(c.getContentId(), c);
                        }
                    }
                    List<RelatedCandidate> result = new ArrayList<>(byId.values());
                    if (result.size() > MAX_CANDIDATES) {
                        result = new ArrayList<>(result.subList(0, MAX_CANDIDATES));
                    }
                    log.info("[Stage1] 폭염 대체(실내) 후보 {}건 확보", result.size());
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
                    List<RelatedCandidate> result = new ArrayList<>(byId.values());
                    if (result.size() > MAX_CANDIDATES) {
                        result = new ArrayList<>(result.subList(0, MAX_CANDIDATES));
                    }
                    log.info("[Stage1] 테마 태그 후보 {}건 확보 (themes={})", result.size(),
                            themes.stream().map(RecommendThemeTag::getTag).toList());
                    return result;
                });
    }

    private Mono<List<RelatedCandidate>> fetchOneTheme(RegionCode region, RecommendThemeTag theme, String query) {
        List<Mono<List<RelatedCandidate>>> calls = new ArrayList<>();
        RecommendThemeTag.CategoryCode[] categoryCodes = theme.getCategoryCodes();
        if (categoryCodes.length > 0) {
            // 분류코드(cat1/cat2/cat3)가 있는 태그는 정밀 조회를 우선 사용 - "사찰"/"온천" 같은 카테고리는
            // 실제 상호명에 그 단어가 안 들어가 키워드 검색만으론 정확도가 낮다.
            Integer primaryType = theme.getContentTypeIds().length > 0 ? theme.getContentTypeIds()[0] : null;
            for (RecommendThemeTag.CategoryCode code : categoryCodes) {
                calls.add(korServiceClient
                        .areaBasedList(primaryType, code.cat1(), code.cat2(), code.cat3(),
                                region.getLDongRegnCd(), region.getLDongSignguCd(), MAX_CANDIDATES, 1, "C")
                        .map(items -> mapKorItems(items, theme.getLabel()))
                        .onErrorReturn(List.of()));
            }
        } else {
            for (int typeId : theme.getContentTypeIds()) {
                calls.add(korServiceClient
                        .areaBasedList(typeId, region.getLDongRegnCd(), region.getLDongSignguCd(), MAX_CANDIDATES, 1, "C")
                        .map(items -> mapKorItems(items, theme.getLabel()))
                        .onErrorReturn(List.of()));
            }
        }
        // 키워드 검색: 사용자 검색어 우선, 없으면 테마 기본 키워드 상위 3개
        List<String> searchWords = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            searchWords.add(query.trim());
        }
        for (String keyword : theme.getKeywords()) {
            if (searchWords.size() >= 3) {
                break;
            }
            if (!searchWords.contains(keyword)) {
                searchWords.add(keyword);
            }
        }
        Integer primaryType = theme.getContentTypeIds().length > 0 ? theme.getContentTypeIds()[0] : null;
        for (String word : searchWords) {
            calls.add(korServiceClient
                    .searchKeyword(word, primaryType, region.getLDongRegnCd(), region.getLDongSignguCd(), MAX_CANDIDATES, 1)
                    .map(items -> mapKorItems(items, theme.getLabel()))
                    .onErrorReturn(List.of()));
            // contentType 없이 한 번 더 (지역 타입이 비는 경우 대비)
            calls.add(korServiceClient
                    .searchKeyword(word, null, region.getLDongRegnCd(), region.getLDongSignguCd(), MAX_CANDIDATES, 1)
                    .map(items -> mapKorItems(items, theme.getLabel()))
                    .onErrorReturn(List.of()));
        }

        return Flux.fromIterable(calls)
                .concatMap(mono -> mono)
                .collectList()
                .map(batches -> {
                    Map<String, RelatedCandidate> byId = new LinkedHashMap<>();
                    for (List<RelatedCandidate> batch : batches) {
                        for (RelatedCandidate c : batch) {
                            if (c.getContentId() == null || c.getPlaceName() == null) {
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
                .areaBasedList(39, region.getLDongRegnCd(), region.getLDongSignguCd(), MAX_CANDIDATES, 1, "C")
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
                    List<RelatedCandidate> result = new ArrayList<>(byId.values());
                    if (result.size() > MAX_CANDIDATES) {
                        result = new ArrayList<>(result.subList(0, MAX_CANDIDATES));
                    }
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

    private List<RelatedCandidate> mapKorItems(List<JsonNode> items, String categoryLcls) {
        List<RelatedCandidate> result = new ArrayList<>();
        int rank = 1;
        for (JsonNode item : items) {
            String name = item.path("title").asText(null);
            if (name == null || name.isBlank()) {
                continue;
            }
            String thumbnail = item.path("firstimage").asText(null);
            String mapX = blankToNull(item.path("mapx").asText(null));
            String mapY = blankToNull(item.path("mapy").asText(null));
            result.add(RelatedCandidate.builder()
                    .placeName(name)
                    .contentId(item.path("contentid").asText(null))
                    .contentTypeId(parseContentTypeId(item))
                    .thumbnailUrl(thumbnail == null || thumbnail.isBlank() ? null : thumbnail)
                    .mapX(mapX)
                    .mapY(mapY)
                    .categoryLcls(categoryLcls)
                    .rank(rank++)
                    .build());
        }
        log.info("[Stage1] {} 후보 {}건 확보", categoryLcls, result.size());
        return result;
    }

    private Integer parseContentTypeId(JsonNode item) {
        String typeId = item.path("contenttypeid").asText(null);
        return typeId == null || typeId.isBlank() ? null : Integer.valueOf(typeId);
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
        return korServiceClient.searchKeyword(candidate.getPlaceName(), null, regnCd, signguCd, 1, 1)
                .map(items -> {
                    if (items.isEmpty()) {
                        log.warn("[Stage1] '{}' KorService2 이름매칭 실패 - 후보에서 제외", candidate.getPlaceName());
                        return candidate;
                    }
                    JsonNode match = items.get(0);
                    candidate.setContentId(match.path("contentid").asText(null));
                    String typeId = match.path("contenttypeid").asText(null);
                    candidate.setContentTypeId(typeId == null ? null : Integer.valueOf(typeId));
                    String thumbnail = match.path("firstimage").asText(null);
                    candidate.setThumbnailUrl(thumbnail == null || thumbnail.isBlank() ? null : thumbnail);
                    if (blankToNull(candidate.getMapX()) == null) {
                        candidate.setMapX(blankToNull(match.path("mapx").asText(null)));
                    }
                    if (blankToNull(candidate.getMapY()) == null) {
                        candidate.setMapY(blankToNull(match.path("mapy").asText(null)));
                    }
                    return candidate;
                })
                .defaultIfEmpty(candidate)
                .onErrorReturn(candidate);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
