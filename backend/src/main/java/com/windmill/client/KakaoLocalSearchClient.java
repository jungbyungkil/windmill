package com.windmill.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.windmill.dto.RelatedCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 카카오 로컬 키워드 장소 검색 - 카카오맵 검색창과 동일한 결과를 그대로 가져온다. "이름으로 검색"
 * (앵커 등록)에서 기존 TourAPI(KorService2) 자체 검색보다 훨씬 폭넓고 정확해서 사용자가 직접 요청함
 * (2026-08-16, "중앙시장" 검색 시 TourAPI 검색은 결과가 부실했는데 카카오맵은 358건을 정확히 찾음).
 * 여기서 나온 결과는 TourAPI contentId가 없으므로 화면엔 그대로 보여주고, 사용자가 실제로 하나를
 * 고르면 그때 Stage1RelatedAttractionService.resolveByNameCascading()으로 이름 매칭한다 -
 * KakaoDirectionsClient와 동일한 REST 키(kakao.rest-api-key)를 재사용, 별도 키 불필요.
 *
 * @see <a href="https://developers.kakao.com/docs/latest/ko/local/dev-guide#search-by-keyword">Kakao Local 키워드 검색</a>
 */
@Slf4j
@Component
public class KakaoLocalSearchClient {

    private static final String SEARCH_URL = "https://dapi.kakao.com/v2/local/search/keyword.json";
    private static final int PAGE_SIZE = 15;

    private final WebClient webClient;
    private final String restApiKey;

    public KakaoLocalSearchClient(WebClient.Builder webClientBuilder,
                                   @Value("${kakao.rest-api-key:}") String restApiKey) {
        this.webClient = webClientBuilder.build();
        this.restApiKey = restApiKey == null ? "" : restApiKey.trim();
    }

    public boolean isConfigured() {
        return !restApiKey.isBlank();
    }

    /** 결과는 contentId/contentTypeId가 비어있는 채로 반환된다 - 선택 시점에 별도로 이름 매칭한다. */
    public Mono<List<RelatedCandidate>> searchKeyword(String query) {
        if (!isConfigured() || query == null || query.isBlank()) {
            return Mono.just(List.of());
        }
        URI uri = UriComponentsBuilder.fromUriString(SEARCH_URL)
                .queryParam("query", query.trim())
                .queryParam("size", PAGE_SIZE)
                .encode()
                .build()
                .toUri();
        return webClient.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + restApiKey)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(this::parse)
                .onErrorResume(e -> {
                    log.warn("[KakaoLocalSearch] '{}' 검색 실패: {}", query, e.toString());
                    return Mono.just(List.of());
                });
    }

    private List<RelatedCandidate> parse(JsonNode root) {
        List<RelatedCandidate> out = new ArrayList<>();
        JsonNode documents = root.path("documents");
        if (!documents.isArray()) {
            return out;
        }
        for (JsonNode doc : documents) {
            String placeName = doc.path("place_name").asText(null);
            if (placeName == null || placeName.isBlank()) {
                continue;
            }
            String addr = blankToNull(doc.path("road_address_name").asText(null));
            if (addr == null) {
                addr = blankToNull(doc.path("address_name").asText(null));
            }
            String category = doc.path("category_name").asText(null);
            String categoryTop = category == null || category.isBlank() ? null : category.split(">")[0].trim();
            out.add(RelatedCandidate.builder()
                    .placeName(placeName)
                    .rank(out.size() + 1)
                    .addr1(addr)
                    .tel(blankToNull(doc.path("phone").asText(null)))
                    .mapX(blankToNull(doc.path("x").asText(null)))
                    .mapY(blankToNull(doc.path("y").asText(null)))
                    .categoryLcls(categoryTop)
                    .homepageUrl(blankToNull(doc.path("place_url").asText(null)))
                    .build());
        }
        return out;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
