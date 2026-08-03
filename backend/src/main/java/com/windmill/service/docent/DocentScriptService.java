package com.windmill.service.docent;

import com.windmill.client.OdiiClient;
import com.windmill.domain.DocentAudio;
import com.windmill.dto.TourAttractionDetail;
import com.windmill.repository.DocentAudioRepository;
import com.windmill.service.ai.OpenAiService;
import com.windmill.service.tourapi.TourAttractionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 도슨트 스크립트 제공 - 한국관광공사 공식 오디오 가이드(오디/Odii)가 있으면 그대로 쓰고(LLM 미호출),
 * 없을 때만 팀 오리지널 캐릭터("바람이") 페르소나로 OpenAI가 스크립트를 생성하는 폴백 경로를 탄다.
 * "가능한 한 관광공사 API로, OpenAI는 불가피한 경우만" 원칙 - Odii가 커버 못 하는 콘텐츠만 LLM이 채운다.
 * TTS 실제 합성은 TtsProvider 구현체 교체로 추후 연결 (LLM 폴백 경로는 현재 NoOpTtsProvider, 스크립트만 제공).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocentScriptService {

    private final TourAttractionService tourAttractionService;
    private final OdiiClient odiiClient;
    private final OpenAiService openAiService;
    private final TtsProvider ttsProvider;
    private final DocentAudioRepository docentAudioRepository;

    public Mono<DocentAudio> getOrGenerate(String contentId, int contentTypeId, String language) {
        return Mono.fromCallable(() -> docentAudioRepository.findByContentIdAndLanguage(contentId, language))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(existing -> existing.map(Mono::just).orElseGet(() -> resolve(contentId, contentTypeId, language)));
    }

    private Mono<DocentAudio> resolve(String contentId, int contentTypeId, String language) {
        return tourAttractionService.getDetail(contentId, contentTypeId)
                .flatMap(detail -> fromOfficialOdii(contentId, detail, language)
                        .switchIfEmpty(generateWithLlm(contentId, detail, language)))
                .flatMap(audio -> Mono.fromCallable(() -> docentAudioRepository.save(audio))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /** 오디 원본 콘텐츠는 국문 위주라 language=ko일 때만 조회한다 - 없으면 Mono.empty()로 LLM 폴백을 유도 */
    private Mono<DocentAudio> fromOfficialOdii(String contentId, TourAttractionDetail detail, String language) {
        if (!odiiClient.isConfigured() || !"ko".equalsIgnoreCase(language) || detail.getTitle() == null) {
            return Mono.empty();
        }
        return odiiClient.searchStory(detail.getTitle(), "ko")
                .flatMap(items -> {
                    if (items.isEmpty()) {
                        return Mono.<DocentAudio>empty();
                    }
                    String script = items.get(0).path("script").asText(null);
                    if (script == null || script.isBlank()) {
                        return Mono.<DocentAudio>empty();
                    }
                    String audioUrl = items.get(0).path("audioUrl").asText(null);
                    log.info("[Docent] '{}' 공식 오디(Odii) 콘텐츠 사용 - LLM 미호출", detail.getTitle());
                    return Mono.just(DocentAudio.builder()
                            .contentId(contentId)
                            .language(language)
                            .scriptText(script)
                            .audioUrl(audioUrl == null || audioUrl.isBlank() ? null : audioUrl)
                            .build());
                });
    }

    private Mono<DocentAudio> generateWithLlm(String contentId, TourAttractionDetail detail, String language) {
        return openAiService.complete(buildPrompt(detail, language))
                .map(script -> script.isBlank() ? fallbackScript(detail) : script)
                .map(script -> DocentAudio.builder()
                        .contentId(contentId)
                        .language(language)
                        .scriptText(script)
                        .audioUrl(ttsProvider.synthesize(script, language))
                        .build());
    }

    private String buildPrompt(TourAttractionDetail detail, String language) {
        return String.format("""
                너는 "바람이"라는 팀 오리지널 캐릭터야 (특정 아이돌 모사 금지). K-pop 브이로그 톤의 친근한 반말체로
                외국인 관광객에게 아래 장소를 소개하는 도슨트 스크립트를 작성해줘. 언어: %s

                장소명: %s
                개요(원본 데이터): %s

                아래 순서로 자연스럽게 이어지는 스크립트를 작성해줘 (섹션 제목 없이 하나의 문단처럼):
                1. 페르소나 인사
                2. 장소명 유래
                3. 역사적 배경
                4. 독특한 특징 1가지
                5. 먹거리/팁 (선택)
                6. 마무리 멘트

                다른 설명 없이 스크립트 본문만 반환해줘.
                """, language, detail.getTitle(), detail.getOverview() == null ? "정보 없음" : detail.getOverview());
    }

    private String fallbackScript(TourAttractionDetail detail) {
        return String.format("안녕! 나는 바람이야. 오늘은 %s를 소개해줄게. %s",
                detail.getTitle(), detail.getOverview() == null ? "여기 정말 멋진 곳이야!" : detail.getOverview());
    }
}
