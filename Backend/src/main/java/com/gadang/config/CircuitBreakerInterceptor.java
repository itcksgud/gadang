package com.gadang.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * RestClient 요청을 서킷브레이커로 감싸는 인터셉터.
 *
 * - OPEN 상태면 {@code acquirePermission()}이 CallNotPermittedException(런타임)을 즉시 던짐
 *   → 외부 API를 기다리지 않고 각 서비스의 catch → fallback 으로 직행.
 * - 실패 집계: IO 예외(타임아웃 포함) + 5xx 응답.
 *   4xx는 성공으로 집계 — Naver 429(rate limit)는 우리가 요청을 줄일 문제지
 *   상대 장애가 아니므로 브레이커를 열면 안 된다.
 */
public class CircuitBreakerInterceptor implements ClientHttpRequestInterceptor {

    private final CircuitBreaker circuitBreaker;

    public CircuitBreakerInterceptor(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        circuitBreaker.acquirePermission();
        long start = System.nanoTime();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            long elapsed = System.nanoTime() - start;
            if (response.getStatusCode().is5xxServerError()) {
                circuitBreaker.onError(elapsed, TimeUnit.NANOSECONDS,
                        new IOException("HTTP " + response.getStatusCode().value() + " from " + request.getURI().getHost()));
            } else {
                circuitBreaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
            }
            return response;
        } catch (IOException | RuntimeException e) {
            circuitBreaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            throw e;
        }
    }
}
