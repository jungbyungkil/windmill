package com.windmill.dto;

import lombok.Builder;
import lombok.Data;

/** 프론트가 런타임에 읽는 공개 설정 (민감정보 제외) */
@Data
@Builder
public class PublicConfigResponse {
    private String kakaoJsKey;
}
