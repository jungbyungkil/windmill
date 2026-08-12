package com.windmill.service.tourapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.windmill.client.KorServiceClient;
import com.windmill.dto.TourAttractionDetail;
import com.windmill.dto.TourAttractionSummary;
import com.windmill.util.HomepageUrlExtractor;
import com.windmill.util.SimpleTtlCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 공사 안내 준수: detailCommon2는 contentId 단건 조회만 가능하므로
 * 목록조회(areaBasedList2/searchKeyword2) → 상세조회(detailCommon2/detailIntro2/detailImage2) 2단계 패턴을 명시적으로 구현.
 * 원본 API 응답은 가공 없이 캐싱하고, 필드 매핑만 여기서 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TourAttractionService {

    private final KorServiceClient korServiceClient;

    private final SimpleTtlCache<String, List<TourAttractionSummary>> listCache =
            new SimpleTtlCache<>(Duration.ofMinutes(30));
    private final SimpleTtlCache<String, TourAttractionDetail> detailCache =
            new SimpleTtlCache<>(Duration.ofMinutes(30));

    public Mono<List<TourAttractionSummary>> listByRegion(Integer contentTypeId, String lDongRegnCd, String lDongSignguCd) {
        String cacheKey = "region:" + contentTypeId + ":" + lDongRegnCd + ":" + lDongSignguCd;
        List<TourAttractionSummary> cached = listCache.get(cacheKey);
        if (cached != null) {
            return Mono.just(cached);
        }
        return korServiceClient.areaBasedList(contentTypeId, lDongRegnCd, lDongSignguCd, 100, 1, "C")
                .map(this::toSummaries)
                .doOnNext(list -> listCache.put(cacheKey, list));
    }

    public Mono<List<TourAttractionSummary>> searchByKeyword(String keyword, Integer contentTypeId,
                                                              String lDongRegnCd, String lDongSignguCd) {
        return korServiceClient.searchKeyword(keyword, contentTypeId, lDongRegnCd, lDongSignguCd, 100, 1)
                .map(this::toSummaries);
    }

    /** 공통정보 + 소개정보(영업시간 등) + 이미지를 조합한 상세 정보 */
    public Mono<TourAttractionDetail> getDetail(String contentId, int contentTypeId) {
        TourAttractionDetail cached = detailCache.get(contentId);
        if (cached != null) {
            return Mono.just(cached);
        }

        Mono<JsonNode> commonMono = korServiceClient.detailCommon(contentId);
        Mono<JsonNode> introMono = korServiceClient.detailIntro(contentId, contentTypeId);
        Mono<List<JsonNode>> imagesMono = korServiceClient.detailImages(contentId);

        return Mono.zip(commonMono.defaultIfEmpty(NullNode.getInstance()),
                        introMono.defaultIfEmpty(NullNode.getInstance()), imagesMono)
                .map(tuple -> buildDetail(contentId, contentTypeId, tuple.getT1(), tuple.getT2(), tuple.getT3()))
                .doOnNext(detail -> detailCache.put(contentId, detail));
    }

    private TourAttractionDetail buildDetail(String contentId, int contentTypeId,
                                              JsonNode common, JsonNode intro, List<JsonNode> images) {
        TourAttractionDetail.TourAttractionDetailBuilder builder = TourAttractionDetail.builder()
                .contentId(contentId)
                .contentTypeId(String.valueOf(contentTypeId));

        if (!common.isNull()) {
            builder.title(common.path("title").asText(null))
                    .overview(common.path("overview").asText(null))
                    .homepage(HomepageUrlExtractor.extract(common.path("homepage").asText(null)))
                    .addr1(common.path("addr1").asText(null))
                    .addr2(common.path("addr2").asText(null))
                    .mapX(common.path("mapx").asText(null))
                    .mapY(common.path("mapy").asText(null))
                    .tel(common.path("tel").asText(null));
        }

        builder.introFields(intro.isNull() ? Map.of() : jsonNodeToMap(intro));
        builder.imageUrls(images.stream()
                .map(img -> img.path("originimgurl").asText(null))
                .filter(url -> url != null && !url.isBlank())
                .collect(Collectors.toList()));

        return builder.build();
    }

    private Map<String, String> jsonNodeToMap(JsonNode node) {
        Map<String, String> map = new ConcurrentHashMap<>();
        Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            String value = node.path(field).asText("");
            if (!value.isBlank()) {
                map.put(field, value);
            }
        }
        return map;
    }

    private List<TourAttractionSummary> toSummaries(List<JsonNode> items) {
        return items.stream().map(TourAttractionSummary::fromJson).collect(Collectors.toList());
    }
}
