package com.windmill.util;

import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * data.go.kr WebClient 쿼리 인코딩 문제 해결 - 실제로 두 모드를 다 시도하며 겪은 결론:
 * - 기본 모드(TEMPLATE_AND_VALUES): 서비스키의 '+'를 인코딩하지 않고 그대로 흘려보내 서버가
 *   공백으로 오인(base64 키가 깨짐). 반대로 이미 퍼센트인코딩된 문자열을 넣으면 '%'를 또
 *   인코딩해버려 이중 인코딩(%2B → %252B)이 남 - 수동으로 부분 치환하면 이 함정에 걸린다.
 * - VALUES_ONLY 모드: '+'는 여전히 안 고쳐지고, 오히려 "[해파랑길] 45코스"처럼 대괄호가 든
 *   실제 장소명에서 "Illegal character in query" 예외가 남.
 * 결론: 두 모드 다 자동 인코딩을 신뢰할 수 없어 EncodingMode.NONE으로 자동 인코딩을 완전히 끄고,
 * 서비스키·한글 검색어처럼 특수문자가 섞일 수 있는 값만 encode()로 직접 한 번만 인코딩한다
 * (URLEncoder는 application/x-www-form-urlencoded 규약이라 공백→'+'로 인코딩하는데, 서버도
 * 같은 규약으로 디코딩하므로 서로 맞아떨어진다). 나머지 값(숫자/영문 상수/우리 쪽에서 생성한
 * 지역코드)은 특수문자가 없어 인코딩 없이 그대로 둬도 안전하다.
 */
public final class TourApiWebClientFactory {

    private TourApiWebClientFactory() {
    }

    public static WebClient create(WebClient.Builder builder, String baseUrl) {
        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory(baseUrl);
        factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
        return builder.clone().uriBuilderFactory(factory).build();
    }

    /** serviceKey, keyword, tAtsNm(장소명) 등 특수문자가 섞일 수 있는 값에 사용 - null-safe */
    public static String encode(String value) {
        return value == null ? null : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
