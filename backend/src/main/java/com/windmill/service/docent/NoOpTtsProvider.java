package com.windmill.service.docent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NoOpTtsProvider implements TtsProvider {

    @Override
    public String synthesize(String scriptText, String language) {
        log.debug("TTS 공급자 미설정 - 스크립트만 제공, 오디오 없음");
        return null;
    }
}
