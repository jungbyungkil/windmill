package com.windmill.service.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RelatedCandidate;
import com.windmill.service.ai.OpenAiService;
import com.windmill.util.PlaceTagSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 4단계 검증 로직 - 4단계: 상황 태그 매칭 + 문장 생성 (LLM 관여는 이 단계만).
 * Stage1~3에서 이미 정렬/필터링이 끝난 순서를 그대로 보존한다 - LLM은 각 후보에 태그를 붙이고
 * 한 문장을 생성할 뿐, 후보를 추가/제거/재정렬하지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Stage4TagMatchingService {

    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Mono<List<RecommendationCandidate>> match(List<RelatedCandidate> candidates, List<String> requestedTags,
                                                       String naturalLanguageQuery) {
        if (candidates.isEmpty()) {
            return Mono.just(List.of());
        }
        final List<String> requested = requestedTags == null ? List.of() : requestedTags;
        if (!openAiService.isConfigured()) {
            return Mono.just(fallback(candidates, requested));
        }
        String prompt = buildPrompt(candidates, requested, naturalLanguageQuery);
        return openAiService.complete(prompt)
                .map(response -> parseResponse(response, candidates, requested))
                .onErrorReturn(fallback(candidates, requested));
    }

    private String buildPrompt(List<RelatedCandidate> candidates, List<String> requestedTags, String query) {
        String candidateLines = candidates.stream()
                .map(c -> String.format("- %s (분류: %s, 여유율: %s)", c.getPlaceName(), c.getCategoryLcls(),
                        c.getCrowdRate() == null ? "정보없음" : String.format("%.0f%%", 100 - c.getCrowdRate())))
                .collect(Collectors.joining("\n"));
        String tagsText = (requestedTags == null || requestedTags.isEmpty()) ? "없음" : String.join(", ", requestedTags);

        return String.format("""
                아래는 이미 영업시간/혼잡도 기준으로 걸러지고 정렬이 끝난 관광지 후보 목록입니다.
                이 순서를 바꾸지 말고, 각 후보에 대해 요청 태그 중 어울리는 것만 골라 매칭하고 한 문장 소개를 만들어주세요.

                사용자 자연어 검색어: %s
                요청 태그 후보: %s

                관광지 목록:
                %s

                규칙:
                - matchedTags는 반드시 위의 "요청 태그 후보"에 있는 것만 사용하세요. 없으면 빈 배열.
                - 전시관·박물관·스테이션·체험관 등 관광/문화시설에 #맛집을 붙이지 마세요.
                - 음식점·식당만 #맛집을 쓰세요.

                각 항목에 대해 아래 JSON 배열 형식으로만 반환하세요. 다른 설명은 하지 마세요.
                [
                  {"placeName": "장소명", "matchedTags": ["#태그"], "oneLiner": "한 문장 소개"}
                ]
                """, query == null ? "없음" : query, tagsText, candidateLines);
    }

    private List<RecommendationCandidate> parseResponse(String response, List<RelatedCandidate> candidates,
                                                          List<String> requestedTags) {
        try {
            String json = OpenAiService.stripJsonFence(response);
            JsonNode array = objectMapper.readTree(json);
            Map<String, JsonNode> byName = new HashMap<>();
            array.forEach(node -> byName.put(node.path("placeName").asText(""), node));

            return candidates.stream().map(c -> {
                JsonNode match = byName.get(c.getPlaceName());
                List<String> matchedTags = List.of();
                String oneLiner = defaultOneLiner(c);
                if (match != null) {
                    if (match.has("matchedTags") && match.path("matchedTags").isArray()) {
                        matchedTags = objectMapper.convertValue(match.path("matchedTags"), List.class);
                    }
                    if (match.has("oneLiner") && !match.path("oneLiner").asText("").isBlank()) {
                        oneLiner = match.path("oneLiner").asText();
                    }
                }
                return toCandidate(c, matchedTags, oneLiner, requestedTags);
            }).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[Stage4] OpenAI 응답 파싱 실패, 기본 문장으로 대체: {}", e.getMessage());
            return fallback(candidates, requestedTags);
        }
    }

    private List<RecommendationCandidate> fallback(List<RelatedCandidate> candidates, List<String> requestedTags) {
        return candidates.stream()
                .map(c -> toCandidate(c, List.of(), defaultOneLiner(c), requestedTags))
                .collect(Collectors.toList());
    }

    private String defaultOneLiner(RelatedCandidate c) {
        if (c.getCrowdRate() != null) {
            return String.format("%s, 지금 여유율 %.0f%%예요.", c.getPlaceName(), 100 - c.getCrowdRate());
        }
        return c.getPlaceName() + "을(를) 추천해요.";
    }

    private RecommendationCandidate toCandidate(RelatedCandidate c, List<String> matchedTags, String oneLiner,
                                                  List<String> requestedTags) {
        List<String> tags = PlaceTagSanitizer.sanitize(
                matchedTags, c.getContentTypeId(), c.getPlaceName(), c.getCategoryLcls(), requestedTags);
        return RecommendationCandidate.builder()
                .contentId(c.getContentId())
                .contentTypeId(c.getContentTypeId())
                .placeName(c.getPlaceName())
                .category(c.getCategoryLcls())
                .thumbnailUrl(c.getThumbnailUrl())
                .crowdRate(c.getCrowdRate())
                .freeRatePercent(c.getCrowdRate() == null ? null : 100 - c.getCrowdRate())
                .matchedTags(tags)
                .oneLiner(oneLiner)
                .rank(c.getRank())
                .addr1(c.getAddr1())
                .tel(c.getTel())
                .isFree(c.getIsFree())
                .useFeeText(c.getUseFeeText())
                .restDateText(c.getRestDateText())
                .closeTime(c.getCloseTime())
                .useTimeText(c.getUseTimeText())
                .homepageUrl(c.getHomepageUrl())
                .distanceKm(c.getDistanceKm())
                .mapX(c.getMapX())
                .mapY(c.getMapY())
                .businessOpen(c.getBusinessOpen())
                .businessStatus(c.getBusinessStatus())
                .strollerText(c.getStrollerText())
                .strollerFriendly(c.getStrollerFriendly())
                .accessibleFriendly(c.isAccessibleFriendly())
                .build();
    }
}
