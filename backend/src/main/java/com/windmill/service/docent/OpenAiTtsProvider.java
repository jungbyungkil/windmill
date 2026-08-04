package com.windmill.service.docent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * OpenAI TTS(/v1/audio/speech)로 도슨트 스크립트를 실제 음성(mp3)으로 합성.
 * openai.api.key를 OpenAiService(스크립트 생성)와 그대로 재사용 - 별도 키 불필요.
 */
@Slf4j
@Component
public class OpenAiTtsProvider implements TtsProvider {

    @Value("${openai.api.key:}")
    private String apiKey;

    @Value("${openai.tts.model:tts-1}")
    private String model;

    @Value("${openai.tts.voice:alloy}")
    private String voice;

    private final WebClient webClient;

    public OpenAiTtsProvider(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.clone().baseUrl("https://api.openai.com").build();
    }

    private boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public Mono<byte[]> synthesize(String scriptText, String language) {
        if (!isConfigured() || scriptText == null || scriptText.isBlank()) {
            log.debug("TTS 미설정 또는 빈 스크립트 - 오디오 합성 생략");
            return Mono.empty();
        }
        Map<String, Object> body = Map.of(
                "model", model,
                "voice", voice,
                "input", scriptText,
                "response_format", "mp3");

        return webClient.post()
                .uri("/v1/audio/speech")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(byte[].class)
                .onErrorResume(e -> {
                    log.error("OpenAI TTS 합성 실패: {}", e.getMessage());
                    return Mono.empty();
                });
    }
}
