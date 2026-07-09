package com.gadang.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpResponse;

class CircuitBreakerInterceptorTest {

    private CircuitBreaker circuitBreaker;
    private CircuitBreakerInterceptor interceptor;
    private HttpRequest request;

    @BeforeEach
    void setUp() {
        // 판정을 빨리 내리는 소형 윈도우: 최근 4건 중 실패율 50% 이상이면 OPEN
        circuitBreaker = CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50)
                .build());
        interceptor = new CircuitBreakerInterceptor(circuitBreaker);
        request = mock(HttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("https://api.example.com/path"));
    }

    @Test
    @DisplayName("연속 IO 실패로 브레이커가 열리면 외부 호출 없이 즉시 실패한다")
    void opensAfterConsecutiveIoFailures_thenFailsFast() throws IOException {
        ClientHttpRequestExecution failing = mock(ClientHttpRequestExecution.class);
        when(failing.execute(any(), any())).thenThrow(new IOException("read timed out"));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], failing))
                    .isInstanceOf(IOException.class);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // OPEN 이후엔 execution이 호출되지 않고 CallNotPermittedException 즉시
        assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], failing))
                .isInstanceOf(CallNotPermittedException.class);
        verify(failing, times(4)).execute(any(), any());
    }

    @Test
    @DisplayName("5xx 응답은 실패로 집계돼 브레이커를 연다")
    void countsServerErrorsAsFailures() throws IOException {
        ClientHttpRequestExecution serverError = mock(ClientHttpRequestExecution.class);
        when(serverError.execute(any(), any()))
                .thenReturn(new MockClientHttpResponse(new byte[0], HttpStatus.INTERNAL_SERVER_ERROR));

        for (int i = 0; i < 4; i++) {
            interceptor.intercept(request, new byte[0], serverError); // 응답은 그대로 반환됨
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    @DisplayName("4xx(429 rate limit 등)는 성공으로 집계돼 브레이커를 열지 않는다")
    void clientErrorsDoNotOpenBreaker() throws IOException {
        ClientHttpRequestExecution rateLimited = mock(ClientHttpRequestExecution.class);
        when(rateLimited.execute(any(), any()))
                .thenReturn(new MockClientHttpResponse(new byte[0], HttpStatus.TOO_MANY_REQUESTS));

        for (int i = 0; i < 8; i++) {
            interceptor.intercept(request, new byte[0], rateLimited);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("정상 응답은 그대로 통과하고 브레이커는 닫힌 상태를 유지한다")
    void passesThroughOnSuccess() throws IOException {
        ClientHttpRequestExecution ok = mock(ClientHttpRequestExecution.class);
        MockClientHttpResponse response = new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        when(ok.execute(any(), any())).thenReturn(response);

        assertThat(interceptor.intercept(request, new byte[0], ok)).isSameAs(response);
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
