package com.windmill.service.docent;

import reactor.core.publisher.Mono;

/**
 * TTS 합성 공급자 - 기본 구현은 OpenAiTtsProvider(OPENAI_API_KEY 재사용). 다른 공급자로 바꾸려면
 * 이 인터페이스의 새 구현체만 추가하면 됨.
 */
public interface TtsProvider {
    /** 합성 성공 시 오디오 바이트(mp3), 미설정/실패 시 Mono.empty() */
    Mono<byte[]> synthesize(String scriptText, String language);
}
