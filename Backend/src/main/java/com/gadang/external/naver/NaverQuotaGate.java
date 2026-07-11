package com.gadang.external.naver;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 네이버 블로그 검색 API 일일 쿼터 게이트.
 *
 * 목적: 새벽 배치 선워밍과 사용자 온디맨드 콜드 조회가 "하나의 일일 예산"을 공유하게 하여,
 * Redis 유실 등으로 콜드 조회가 폭주해도 네이버 일 쿼터(25,000)를 절대 넘지 않게 한다.
 * 예산 소진 시 acquire가 false → 호출부는 외부 호출 없이 0점으로 graceful degrade 한다.
 *
 * 게이트는 실제 HTTP 호출(fetchBlogCount) 진입 전 1건씩 acquire 한다.
 * 캐시 히트는 fetchBlogCount를 타지 않으므로 예산을 소비하지 않는다.
 */
@Slf4j
@Component
public class NaverQuotaGate {

    /** 네이버 블로그 검색 일 쿼터(25,000)에서 429 재시도·여유분을 뺀 안전 상한 */
    private static final int DAILY_BUDGET = 24_000;

    private final AtomicInteger used = new AtomicInteger(0);
    private volatile LocalDate windowDate = LocalDate.now();

    /**
     * 호출 1건 분의 예산을 확보한다.
     * @return 예산이 남아 호출을 진행해도 되면 true, 소진되어 degrade 해야 하면 false
     */
    public boolean tryAcquire() {
        rolloverIfNewDay();
        int now = used.incrementAndGet();
        if (now > DAILY_BUDGET) {
            used.decrementAndGet();   // 초과분 롤백 — 허용 건수는 정확히 DAILY_BUDGET 이하
            return false;
        }
        return true;
    }

    /** 날짜가 바뀌면 예산을 리셋한다(자정 롤오버). */
    private void rolloverIfNewDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(windowDate)) {
            synchronized (this) {
                if (!today.equals(windowDate)) {
                    used.set(0);
                    windowDate = today;
                    log.info("[네이버쿼터] 일일 예산 리셋 ({}) — 상한 {}", today, DAILY_BUDGET);
                }
            }
        }
    }

    /** 관측용 — 오늘 사용량 */
    public int used() {
        return used.get();
    }

    /** 관측용 — 오늘 남은 예산 */
    public int remaining() {
        return Math.max(0, DAILY_BUDGET - used.get());
    }
}
