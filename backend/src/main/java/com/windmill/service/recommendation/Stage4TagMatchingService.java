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
        return match(candidates, requestedTags, naturalLanguageQuery, null);
    }

    /**
     * @param childAges 동반 자녀 만 나이 - 있으면 "#아이동반"을 명시적으로 고르지 않았어도 매칭 후보에
     *                  자동으로 넣고, LLM 프롬프트에 나이대 힌트를 줘서 "아이랑 가기 좋은 곳" 같은
     *                  문장이 자연스럽게 나오게 한다(Stage1 자체를 바꾸지 않고 이 단계에서만 반영 -
     *                  실제 순위 보정은 이미 AgeGroupRanking이 Stage3 뒤·Stage4 앞에서 처리함).
     */
    public Mono<List<RecommendationCandidate>> match(List<RelatedCandidate> candidates, List<String> requestedTags,
                                                       String naturalLanguageQuery, List<Integer> childAges) {
        if (candidates.isEmpty()) {
            return Mono.just(List.of());
        }
        List<String> requested = requestedTags == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(requestedTags);
        String ageBand = AgeGroupRanking.youngestAgeBandLabel(childAges);
        if (ageBand != null && !requested.contains("#아이동반")) {
            requested.add("#아이동반");
        }
        final List<String> finalRequested = List.copyOf(requested);
        if (!openAiService.isConfigured()) {
            return Mono.just(fallback(candidates, finalRequested));
        }
        String prompt = buildPrompt(candidates, finalRequested, naturalLanguageQuery, ageBand);
        return openAiService.complete(prompt)
                .map(response -> parseResponse(response, candidates, finalRequested))
                .onErrorReturn(fallback(candidates, finalRequested));
    }

    /**
     * LLM을 아예 건너뛴다(RecommendationRequest.skipLlm) - OpenAI 미설정 시 쓰는 fallback과 동일한
     * 키워드 기반 문장/태그를 즉시 반환한다. Stage1~3이 이미 순서를 정했으므로(이 클래스 상단 문서
     * 참고) 어떤 장소가 뽑히는지는 LLM을 쓸 때와 동일하고, matchedTags/oneLiner만 더 단순해진다.
     */
    public Mono<List<RecommendationCandidate>> matchWithoutLlm(List<RelatedCandidate> candidates,
                                                                  List<String> requestedTags,
                                                                  List<Integer> childAges) {
        if (candidates.isEmpty()) {
            return Mono.just(List.of());
        }
        List<String> requested = requestedTags == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(requestedTags);
        String ageBand = AgeGroupRanking.youngestAgeBandLabel(childAges);
        if (ageBand != null && !requested.contains("#아이동반")) {
            requested.add("#아이동반");
        }
        return Mono.just(fallback(candidates, List.copyOf(requested)));
    }

    private String buildPrompt(List<RelatedCandidate> candidates, List<String> requestedTags, String query, String childAgeBand) {
        String candidateLines = candidates.stream()
                .map(c -> String.format("- %s (분류: %s, 여유율: %s)", c.getPlaceName(), c.getCategoryLcls(),
                        c.getCrowdRate() == null ? "정보없음" : String.format("%.0f%%", 100 - c.getCrowdRate())))
                .collect(Collectors.joining("\n"));
        String tagsText = (requestedTags == null || requestedTags.isEmpty()) ? "없음" : String.join(", ", requestedTags);
        String childHint = childAgeBand == null ? "" : String.format("""

                동반 자녀: %s. 자녀와 함께 가기 좋은 후보(체험관·키즈카페·실내 놀이시설·박물관 등)에는
                자연스럽게 "아이랑 가기 좋은 곳"이라는 느낌이 드러나는 한 문장을 만들어주세요. 자녀와 안
                맞는 곳(성인 전용, 유흥 시설 등)은 억지로 아이 동반 문구를 넣지 마세요.
                """, childAgeBand);

        return String.format("""
                아래는 이미 영업시간/혼잡도 기준으로 걸러지고 정렬이 끝난 관광지 후보 목록입니다.
                이 순서를 바꾸지 말고, 각 후보에 대해 요청 태그 중 어울리는 것만 골라 매칭하고 한 문장 소개를 만들어주세요.

                사용자 자연어 검색어: %s
                요청 태그 후보: %s
                %s
                관광지 목록:
                %s

                규칙:
                - matchedTags는 반드시 위의 "요청 태그 후보"에 있는 것만 사용하세요. 없으면 빈 배열.
                - 전시관·박물관·스테이션·체험관 등 관광/문화시설에 #맛집·#카페·#한식·#중식·#일식·#양식 같은
                  음식 관련 태그를 붙이지 마세요 - 실제 음식점·카페에만, 그것도 실제 업종에 맞는 태그만
                  붙이세요(예: 한식당엔 #한식만, 카페엔 #카페만 - 여러 개를 억지로 겹쳐 붙이지 않기).

                각 항목에 대해 아래 JSON 배열 형식으로만 반환하세요. 다른 설명은 하지 마세요.
                [
                  {"placeName": "장소명", "matchedTags": ["#태그"], "oneLiner": "한 문장 소개"}
                ]
                """, query == null ? "없음" : query, tagsText, childHint, candidateLines);
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
                .estimatedCostPerPerson(c.getEstimatedCostPerPerson())
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
                .ageRangeText(c.getAgeRangeText())
                .build();
    }
}
