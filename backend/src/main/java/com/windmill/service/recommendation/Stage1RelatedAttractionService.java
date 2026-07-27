package com.windmill.service.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.client.KorServiceClient;
import com.windmill.client.RelatedAttractionClient;
import com.windmill.dto.RelatedCandidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    private final RelatedAttractionClient relatedAttractionClient;
    private final KorServiceClient korServiceClient;

    @Value("${windmill.region.sokcho.legacy-area-cd}")
    private String areaCd;

    @Value("${windmill.region.sokcho.legacy-signgu-cd}")
    private String signguCd;

    @Value("${windmill.region.sokcho.new-region-cd}")
    private String lDongRegnCd;

    @Value("${windmill.region.sokcho.new-signgu-cd}")
    private String lDongSignguCd;

    public Mono<List<RelatedCandidate>> fetch(String seedPlaceName) {
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
     * 연관관광지 API는 KorService2의 contentId와 무관한 자체 코드만 제공하므로,
     * 관광지명(placeName) 기준 키워드검색(searchKeyword2)으로 조인해 contentId/contentTypeId를 채운다.
     * 매칭 실패(동명이인/표기 차이 등)한 후보는 제외하고 로그만 남긴다.
     */
    public Mono<List<RelatedCandidate>> resolveContentIds(List<RelatedCandidate> candidates) {
        return Flux.fromIterable(candidates)
                .flatMap(this::resolveOne, EXTERNAL_CALL_CONCURRENCY)
                .filter(c -> c.getContentId() != null)
                .collectList()
                .doOnNext(list -> log.info("[Stage1] KorService2 이름매칭 성공 {}건 / {}건 중", list.size(), candidates.size()));
    }

    private Mono<RelatedCandidate> resolveOne(RelatedCandidate candidate) {
        return korServiceClient.searchKeyword(candidate.getPlaceName(), null, lDongRegnCd, lDongSignguCd, 1, 1)
                .map(items -> {
                    if (items.isEmpty()) {
                        log.warn("[Stage1] '{}' KorService2 이름매칭 실패 - 후보에서 제외", candidate.getPlaceName());
                        return candidate;
                    }
                    JsonNode match = items.get(0);
                    candidate.setContentId(match.path("contentid").asText(null));
                    String typeId = match.path("contenttypeid").asText(null);
                    candidate.setContentTypeId(typeId == null ? null : Integer.valueOf(typeId));
                    return candidate;
                })
                .defaultIfEmpty(candidate)
                .onErrorReturn(candidate);
    }
}
