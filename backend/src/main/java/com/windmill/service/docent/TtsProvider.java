package com.windmill.service.docent;

/**
 * 실제 TTS 합성 공급자는 미정 - 인터페이스만 정의하고 기본 구현은 NoOpTtsProvider(오디오 없이 스크립트만 제공).
 * 공급자 결정 시 이 인터페이스의 새 구현체만 추가하면 됨.
 */
public interface TtsProvider {
    /** 합성 성공 시 오디오 URL(캐싱 경로), 미구현/실패 시 null */
    String synthesize(String scriptText, String language);
}
