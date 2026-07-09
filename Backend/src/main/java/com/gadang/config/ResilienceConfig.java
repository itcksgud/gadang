package com.gadang.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 외부 API 서킷브레이커 설정 (Resilience4j 코어).
 *
 * Spring Boot 어댑터(resilience4j-spring-boot3) 대신 코어 라이브러리를
 * RestClient 인터셉터로 직접 데코레이트한다 — Boot 4 호환 리스크 회피 + 동작이 코드에 드러남.
 *
 * 목적: ODsay 등 외부 API가 느려지거나 죽었을 때, 매 요청이 read timeout(10s)까지
 * Tomcat 스레드를 물고 늘어지는 것을 차단. 브레이커가 열리면 즉시 실패 →
 * 각 서비스의 기존 catch → fallback(Haversine, BusFrequencyTable 등) 경로로 넘어간다.
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(MeterRegistry meterRegistry) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                // 최근 20건 중 실패율 50% 이상이면 OPEN (최소 10건 관측 후 판단)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50)
                // 개별 호출 5초 초과를 '느린 호출'로 집계 — 80% 이상 느리면 타임아웃 전에 선제 차단
                .slowCallDurationThreshold(Duration.ofSeconds(5))
                .slowCallRateThreshold(80)
                // OPEN 30초 후 HALF_OPEN — 시험 호출 3건으로 복구 판정
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        // 브레이커 상태·실패율을 /actuator/metrics (resilience4j_circuitbreaker_*)로 노출
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry;
    }
}
