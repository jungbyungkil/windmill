package com.windmill.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    /**
     * 각 client가 서로 다른 baseUrl을 쓰기 때문에 인코딩 모드 고정은 여기서 할 수 없다
     * (uriBuilderFactory를 여기서 baseUrl 없이 지정하면 각 client의 .baseUrl(x) 호출이 무시되어
     * 전부 localhost로 요청이 나간다 - 실제로 겪은 버그). 인코딩 모드는 client별로
     * TourApiWebClientFactory.create(builder, baseUrl)에서 baseUrl과 함께 설정한다.
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
