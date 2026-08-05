package com.windmill.service.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.client.KorServiceClient;
import com.windmill.client.PetFriendlyAttractionClient;
import com.windmill.client.RelatedAttractionClient;
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
     */
    private Mono<List<RelatedCandidate>> fetchPetFriendly(RegionCode region, String seedPlaceName) {
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
                .map(items -> {
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
                                .categoryLcls("실내")
                                .rank(rank++)
                                .build());
                    }
                    log.info("[Stage1] 우천 대체(실내) 후보 {}건 확보", result.size());
                    return result;
                });
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
                .flatMap(c -> resolveOne(c, region), EXTERNAL_CALL_CONCURRENCY)
                .filter(c -> c.getContentId() != null)
                .collectList()
                .doOnNext(list -> log.info("[Stage1] KorService2 이름매칭 성공 {}건 / {}건 중", list.size(), candidates.size()));
    }

    private Mono<RelatedCandidate> resolveOne(RelatedCandidate candidate, RegionCode region) {
        if (candidate.getContentId() != null) {
            // 반려동물동반 소스(fetchPetFriendly)는 이미 contentId가 채워져 있어 재조회 불필요
            return Mono.just(candidate);
        }
        return korServiceClient.searchKeyword(candidate.getPlaceName(), null, region.getLDongRegnCd(), region.getLDongSignguCd(), 1, 1)
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
                    return candidate;
                })
                .defaultIfEmpty(candidate)
                .onErrorReturn(candidate);
    }
}
