package com.windmill.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    /**
     * 각 client가 서로 다른 baseUrl을 쓰기 때문에 인코딩 모드 고정은 여기서 할 수 없다
     * (uriBuilderFactory를 여기서 baseUrl 없이 지정하면 각 client의 .baseUrl(x) 호출이 무시되어
     * 전부 localhost로 요청이 나간다 - 실제로 겪은 버그). 인코딩 모드는 client별로
     * TourApiWebClientFactory.create(builder, baseUrl)에서 baseUrl과 함께 설정한다.
     *
     * ⚠ 타임아웃 미설정 시 data.go.kr 쪽이 응답 없이 멈추면(가끔 발생) 호출이 무한 대기하다가
     * Spring MVC의 async 타임아웃(~30초, 미설정 시 컨테이너 기본값)에 걸려 컨트롤러 전체가
     * 503(AsyncRequestTimeoutException)으로 죽는다 - 각 client의 onErrorReturn/onErrorResume
     * 폴백이 있어도 애초에 에러가 나야 발동하므로 소용없었다. connectTimeout/responseTimeout을
     * 여기서 걸어 개별 외부 호출이 먼저 실패하게 만들어야 폴백이 동작하고 파이프라인이 죽지 않는다.
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .responseTimeout(Duration.ofSeconds(15));
        return WebClient.builder().clientConnector(new ReactorClientHttpConnector(httpClient));
    }
}
