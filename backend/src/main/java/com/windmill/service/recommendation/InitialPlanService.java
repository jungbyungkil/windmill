package com.windmill.service.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.windmill.dto.RecommendationCandidate;
import com.windmill.dto.RecommendationRequest;
import com.windmill.service.ai.OpenAiService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 5단계(초기 일정 초안 배치) - Stage1~4가 이미 검증한 실제 후보만 사용해 순서와 제안 방문 시각(HH:mm)만
 * LLM이 배정한다. 새 장소를 만들지 않는다 - RecommendationPipeline의 "AI가 임의로 추천하지 않는다"
 * 원칙을 그대로 승계 (Stage4TagMatchingService 주석 참고).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InitialPlanService {

    private static final LocalTime DEFAULT_START = LocalTime.of(9, 0);
    private static final int DEFAULT_INTERVAL_MINUTES = 90;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final RecommendationPipeline recommendationPipeline;
    private final OpenAiService openAiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Mono<List<RecommendationCandidate>> draft(RecommendationRequest request, int placeCount) {
        return recommendationPipeline.recommend(request)
                .map(candidates -> candidates.stream()
                        .limit(Math.max(placeCount, 1))
                        .collect(Collectors.toList()))
                .flatMap(this::assignSchedule);
    }

    private Mono<List<RecommendationCandidate>> assignSchedule(List<RecommendationCandidate> candidates) {
        if (candidates.isEmpty() || !openAiService.isConfigured()) {
            return Mono.just(fallbackSchedule(candidates));
        }
        return openAiService.complete(buildPrompt(candidates))
                .map(response -> applySuggestedTimes(response, candidates))
                .onErrorReturn(fallbackSchedule(candidates));
    }

    private String buildPrompt(List<RecommendationCandidate> candidates) {
        String lines = candidates.stream()
                .map(c -> String.format("- %s (%s)", c.getPlaceName(),
                        c.getCategory() == null ? "분류없음" : c.getCategory()))
                .collect(Collectors.joining("\n"));
        return String.format("""
                아래는 이미 검증된 실제 관광지 후보 목록입니다. 새로운 장소를 만들지 말고,
                이 목록에 있는 장소만 가지고 하루 여행 코스로 자연스러운 방문 순서와
                방문 시각(HH:mm, 09:00~19:00 사이)을 정해주세요.

                관광지 목록:
                %s

                아래 JSON 배열 형식으로만 반환하세요. 다른 설명은 하지 마세요.
                [
                  {"placeName": "장소명", "suggestedTime": "HH:mm"}
                ]
                """, lines);
    }

    private List<RecommendationCandidate> applySuggestedTimes(String response, List<RecommendationCandidate> candidates) {
        try {
            String json = OpenAiService.stripJsonFence(response);
            JsonNode array = objectMapper.readTree(json);
            Map<String, String> timeByName = new HashMap<>();
            array.forEach(node -> timeByName.put(node.path("placeName").asText(""), node.path("suggestedTime").asText(null)));

            for (RecommendationCandidate c : candidates) {
                c.setSuggestedTime(timeByName.get(c.getPlaceName()));
            }

            boolean anyMissing = candidates.stream()
                    .anyMatch(c -> c.getSuggestedTime() == null || c.getSuggestedTime().isBlank());
            return anyMissing ? fallbackSchedule(candidates) : sortByTime(candidates);
        } catch (Exception e) {
            log.warn("[InitialPlan] OpenAI 응답 파싱 실패, 기본 시간표로 대체: {}", e.getMessage());
            return fallbackSchedule(candidates);
        }
    }

    private List<RecommendationCandidate> fallbackSchedule(List<RecommendationCandidate> candidates) {
        LocalTime cursor = DEFAULT_START;
        for (RecommendationCandidate c : candidates) {
            c.setSuggestedTime(cursor.format(TIME_FORMAT));
            cursor = cursor.plusMinutes(DEFAULT_INTERVAL_MINUTES);
        }
        return candidates;
    }

    private List<RecommendationCandidate> sortByTime(List<RecommendationCandidate> candidates) {
        return candidates.stream()
                .sorted((a, b) -> a.getSuggestedTime().compareTo(b.getSuggestedTime()))
                .collect(Collectors.toList());
    }
}
