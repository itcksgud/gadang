package com.gadang.external.naver;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 일일 쿼터 게이트 순수 로직 테스트 (Spring 컨텍스트·외부 의존 없음).
 * 핵심 계약: (1) 허용 건수는 정확히 일일 예산까지, (2) 동시성 하에서도 예산 초과 없음.
 */
class NaverQuotaGateTest {

    /** NaverQuotaGate.DAILY_BUDGET 와 동일해야 한다 */
    private static final int BUDGET = 24_000;

    @Test
    void allowsExactlyDailyBudgetThenDegrades() {
        NaverQuotaGate gate = new NaverQuotaGate();

        int allowed = 0;
        for (int i = 0; i < BUDGET + 100; i++) {
            if (gate.tryAcquire()) allowed++;
        }

        assertEquals(BUDGET, allowed, "허용 건수는 정확히 일일 예산까지여야 한다");
        assertEquals(BUDGET, gate.used());
        assertEquals(0, gate.remaining());
        assertFalse(gate.tryAcquire(), "예산 소진 후에는 항상 거부되어야 한다");
    }

    @Test
    void neverExceedsBudgetUnderConcurrency() throws InterruptedException {
        NaverQuotaGate gate = new NaverQuotaGate();
        int threads = 16;
        int attemptsPerThread = 3_000; // 16*3000 = 48,000 > 24,000

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger allowed = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < attemptsPerThread; i++) {
                    if (gate.tryAcquire()) allowed.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "테스트가 시간 내 끝나야 한다");

        assertEquals(BUDGET, allowed.get(), "동시성 하에서도 허용 총량은 예산과 정확히 같아야 한다");
        assertTrue(gate.used() <= BUDGET, "used 카운터가 예산을 초과 저장하면 안 된다");
    }
}
