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
    private final String firebaseApiKey;
    private final String firebaseAuthDomain;
    private final String firebaseProjectId;
    private final String firebaseMessagingSenderId;
    private final String firebaseAppId;
    private final String firebaseVapidKey;

    public PublicConfigController(
            @Value("${kakao.js-key:}") String kakaoJsKey,
            @Value("${firebase.web.api-key:}") String firebaseApiKey,
            @Value("${firebase.web.auth-domain:}") String firebaseAuthDomain,
            @Value("${firebase.web.project-id:}") String firebaseProjectId,
            @Value("${firebase.web.messaging-sender-id:}") String firebaseMessagingSenderId,
            @Value("${firebase.web.app-id:}") String firebaseAppId,
            @Value("${firebase.web.vapid-key:}") String firebaseVapidKey) {
        this.kakaoJsKey = trimToEmpty(kakaoJsKey);
        this.firebaseApiKey = trimToEmpty(firebaseApiKey);
        this.firebaseAuthDomain = trimToEmpty(firebaseAuthDomain);
        this.firebaseProjectId = trimToEmpty(firebaseProjectId);
        this.firebaseMessagingSenderId = trimToEmpty(firebaseMessagingSenderId);
        this.firebaseAppId = trimToEmpty(firebaseAppId);
        this.firebaseVapidKey = trimToEmpty(firebaseVapidKey);
    }

    @GetMapping
    public PublicConfigResponse get() {
        boolean firebaseReady = !firebaseApiKey.isBlank()
                && !firebaseAuthDomain.isBlank()
                && !firebaseProjectId.isBlank()
                && !firebaseMessagingSenderId.isBlank()
                && !firebaseAppId.isBlank()
                && !firebaseVapidKey.isBlank();
        return PublicConfigResponse.builder()
                .kakaoJsKey(blankToNull(kakaoJsKey))
                .firebaseApiKey(firebaseReady ? firebaseApiKey : null)
                .firebaseAuthDomain(firebaseReady ? blankToNull(firebaseAuthDomain) : null)
                .firebaseProjectId(firebaseReady ? firebaseProjectId : null)
                .firebaseMessagingSenderId(firebaseReady ? firebaseMessagingSenderId : null)
                .firebaseAppId(firebaseReady ? firebaseAppId : null)
                .firebaseVapidKey(firebaseReady ? firebaseVapidKey : null)
                .build();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
