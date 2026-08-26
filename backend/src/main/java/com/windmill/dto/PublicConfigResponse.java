package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

/** 프론트가 런타임에 읽는 공개 설정 (민감정보 제외). 서비스 계정 키는 절대 넣지 않는다. */
@Data
@Builder
public class PublicConfigResponse {
    private String kakaoJsKey;
    /** Firebase 웹 앱 설정 - 비어 있으면 프론트는 푸시 토큰 발급을 건너뛴다 */
    private String firebaseApiKey;
    private String firebaseAuthDomain;
    private String firebaseProjectId;
    private String firebaseMessagingSenderId;
    private String firebaseAppId;
    private String firebaseVapidKey;
}
