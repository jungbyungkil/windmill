package com.windmill.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Claude API 래퍼 - 4단계 파이프라인의 마지막 단계(태그매칭·문장생성)와 도슨트 스크립트 생성에만 사용.
 * 후보 필터링/정렬에는 절대 관여하지 않음 (Stage4TagMatchingService 참고).
 */
@Slf4j
@Service
public class ClaudeService {

    @Value("${claude.api.key:}")
    private String apiKey;

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ClaudeService(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.clone().baseUrl("https://api.anthropic.com").build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public Mono<String> complete(String prompt) {
        if (!isConfigured()) {
            return Mono.just("");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("model", "claude-sonnet-4-6");
        body.put("max_tokens", 2000);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));

        return webClient.post()
                .uri("/v1/messages")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .map(this::extractText)
                .onErrorResume(e -> {
                    log.error("Claude API 호출 실패: {}", e.getMessage());
                    return Mono.just("");
                });
    }

    /** 응답에서 ```json 코드펜스를 제거하고 순수 JSON 문자열만 반환 */
    public static String stripJsonFence(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("```json\\n?", "").replaceAll("```\\n?", "").trim();
        }
        return trimmed;
    }

    private String extractText(String responseBody) {
        try {
            JsonNode node = objectMapper.readTree(responseBody);
            return node.path("content").get(0).path("text").asText();
        } catch (Exception e) {
            log.warn("Claude 응답 파싱 실패: {}", e.getMessage());
            return "";
        }
    }
}
