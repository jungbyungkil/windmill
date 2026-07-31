package com.windmill.service.recommendation;

import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RecommendationRequest;
import com.windmill.dto.RelatedCandidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
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

    public Mono<List<RecommendationCandidate>> recommend(RecommendationRequest request) {
        log.info("[Pipeline] 추천 시작 - seed={}, tags={}, avoid={}",
                request.getSeedPlaceName(), request.getTags(), request.getAvoidanceHint());

        Set<String> exclude = request.getExcludeContentIds() == null
                ? Set.of()
                : Set.copyOf(request.getExcludeContentIds());
        Set<String> excludeNames = request.getExcludePlaceNames() == null
                ? Set.of()
                : Set.copyOf(request.getExcludePlaceNames());

        return stage1.fetch(request.getSeedPlaceName())
                .flatMap(stage1::resolveContentIds)
                .map(list -> list.stream()
                        .filter(c -> !exclude.contains(c.getContentId()))
                        .collect(Collectors.toList()))
                .flatMap(stage2::filter)
                .flatMap(stage3::filter)
                .flatMap(list -> stage4.match(list, request.getTags(), request.getNaturalLanguageQuery()))
                .map(list -> list.stream()
                        .filter(c -> !excludeNames.contains(c.getPlaceName()))
                        .collect(Collectors.toList()))
                .map(list -> applyAvoidanceOrdering(list, request.getAvoidanceHint()))
                .doOnNext(list -> log.info("[Pipeline] 최종 추천 {}건", list.size()));
    }

    /**
     * 트리거 우선회피 정렬. 혼잡도 트리거는 Stage3에서 이미 여유율 순으로 정렬되어 있으므로 그대로 두고,
     * 기상 트리거는 #실내 태그가 매칭된 후보를 앞으로 당긴다.
     */
    private List<RecommendationCandidate> applyAvoidanceOrdering(List<RecommendationCandidate> candidates,
                                                                   RecommendationRequest.AvoidanceHint hint) {
        if (hint == RecommendationRequest.AvoidanceHint.WEATHER) {
            return candidates.stream()
                    .sorted(Comparator.comparing((RecommendationCandidate c) ->
                            c.getMatchedTags() != null && c.getMatchedTags().contains("#실내") ? 0 : 1))
                    .collect(Collectors.toList());
        }
        return candidates;
    }
}
