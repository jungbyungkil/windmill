package com.windmill.controller;

import com.windmill.dto.PublicConfigResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 공개 가능한 런타임 설정.
 * - JS 지도 키는 본질적으로 공개키이므로 제공 가능
 * - REST/OpenAI 등 민감키는 절대 노출 금지
 */
@RestController
@RequestMapping("/api/public-config")
@CrossOrigin(origins = "*")
public class PublicConfigController {

    private final String kakaoJsKey;

    public PublicConfigController(@Value("${kakao.js-key:}") String kakaoJsKey) {
        this.kakaoJsKey = kakaoJsKey == null ? "" : kakaoJsKey.trim();
    }

    @GetMapping
    public PublicConfigResponse get() {
        return PublicConfigResponse.builder()
                .kakaoJsKey(kakaoJsKey.isBlank() ? null : kakaoJsKey)
                .build();
    }
}
