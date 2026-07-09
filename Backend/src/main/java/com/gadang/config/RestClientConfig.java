package com.gadang.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /** 외부 API 공통 타임아웃 — 무한 대기 방지 (연결 3초 / 응답 10초) */
    private static SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3_000);
        f.setReadTimeout(10_000);
        return f;
    }

    /** API별 이름 붙은 서킷브레이커 — 한 API의 장애가 다른 API 호출을 차단하지 않도록 분리 */
    private static CircuitBreakerInterceptor breaker(CircuitBreakerRegistry registry, String name) {
        return new CircuitBreakerInterceptor(registry.circuitBreaker(name));
    }

    @Value("${kakao.api.key}")
    private String kakaoApiKey;

    @Value("${odsay.api.key}")
    private String odsayApiKey;

    @Bean("kakaoRestClient")
    public RestClient kakaoRestClient(CircuitBreakerRegistry cbRegistry) {
        return RestClient.builder().requestFactory(timeoutFactory())
                .baseUrl("https://dapi.kakao.com")
                .defaultHeader("Authorization", "KakaoAK " + kakaoApiKey)
                // 서버-서버 호출 시 KA 헤더 필요 (Kakao 보안 정책)
                // KA 형식: sdk/{version} os/{platform} origin/{domain}
                .defaultHeader("KA", "sdk/1.0.0 os/java origin/https://gadang.local")
                .defaultHeader("Referer", "https://gadang.local")
                .requestInterceptor(breaker(cbRegistry, "kakao"))
                .build();
    }

    @Bean("odsayRestClient")
    public RestClient odsayRestClient(CircuitBreakerRegistry cbRegistry) {
        return RestClient.builder().requestFactory(timeoutFactory())
                .baseUrl("https://api.odsay.com/v1/api")
                .requestInterceptor(breaker(cbRegistry, "odsay"))
                .build();
    }

    @Bean("naverRestClient")
    public RestClient naverRestClient(
            @Value("${naver.client-id}") String clientId,
            @Value("${naver.client-secret}") String clientSecret,
            CircuitBreakerRegistry cbRegistry) {
        return RestClient.builder().requestFactory(timeoutFactory())
                .baseUrl("https://openapi.naver.com/v1")
                .defaultHeader("X-Naver-Client-Id", clientId)
                .defaultHeader("X-Naver-Client-Secret", clientSecret)
                .requestInterceptor(breaker(cbRegistry, "naver"))
                .build();
    }

    @Bean("tourRestClient")
    public RestClient tourRestClient(CircuitBreakerRegistry cbRegistry) {
        return RestClient.builder().requestFactory(timeoutFactory())
                .baseUrl("https://apis.data.go.kr/B551011/KorService2")
                .requestInterceptor(breaker(cbRegistry, "tour"))
                .build();
    }

    @Bean("openMeteoRestClient")
    public RestClient openMeteoRestClient(CircuitBreakerRegistry cbRegistry) {
        return RestClient.builder().requestFactory(timeoutFactory())
                .baseUrl("https://api.open-meteo.com/v1")
                .requestInterceptor(breaker(cbRegistry, "openMeteo"))
                .build();
    }

    /** 가당 RAG 서버(FastAPI) — 공유 코스 의미 검색 (AI Server 분리) */
    @Bean("ragRestClient")
    public RestClient ragRestClient(@Value("${gadang.rag.base-url:http://localhost:8000}") String baseUrl,
                                    CircuitBreakerRegistry cbRegistry) {
        return RestClient.builder().requestFactory(timeoutFactory())
                .baseUrl(baseUrl)
                .requestInterceptor(breaker(cbRegistry, "rag"))
                .build();
    }

    @Bean("korailTrainRestClient")
    public RestClient korailTrainRestClient(CircuitBreakerRegistry cbRegistry) {
        return RestClient.builder().requestFactory(timeoutFactory())
                // 2026-03 개편: TrainInfoService → TrainInfo (오퍼레이션도 Get~ 대문자로 변경)
                .baseUrl("https://apis.data.go.kr/1613000/TrainInfo")
                .requestInterceptor(breaker(cbRegistry, "korail"))
                .build();
    }
}
