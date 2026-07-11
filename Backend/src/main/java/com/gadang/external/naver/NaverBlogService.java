package com.gadang.external.naver;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 네이버 블로그 검색 API — 장소명 언급 수 → trendScore 계산
 *
 * 기준:
 *   10,000건 미만  → 0점  (동네 공원, 주민 시설 등 — 여행 가치 없음)
 *   10,000~        → 로그 스케일로 0~100점
 *   1,000,000+     → 최대 100점
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NaverBlogService {

    private static final int    MIN_COUNT      = 10_000;   // 이 미만이면 탈락
    private static final double LOG_BASE_COUNT = 1_000_000.0; // 100점 기준

    /** 네이버 검색 API는 일일 쿼터와 별개로 초당 호출 속도 제한(429)이 있다.
     *  스레드 수만 줄여서는 코스 생성이 겹칠 때 총량이 지켜지지 않으므로,
     *  모든 스레드가 공유하는 전역 슬롯으로 호출 시작 간격을 강제한다. (~8건/초) */
    private static final long MIN_INTERVAL_MS = 125;
    private static final Object THROTTLE = new Object();
    private static long nextSlotAt = 0;

    /** 다음 호출 슬롯을 예약하고 자기 차례까지 대기 */
    private static void acquireSlot() throws InterruptedException {
        long waitMs;
        synchronized (THROTTLE) {
            long now = System.currentTimeMillis();
            long slot = Math.max(now, nextSlotAt);
            nextSlotAt = slot + MIN_INTERVAL_MS;
            waitMs = slot - now;
        }
        if (waitMs > 0) Thread.sleep(waitMs);
    }

    /** 429 발생 시 자기만 백오프하면 다른 스레드가 계속 때려서 재시도가 전부 소진된다 —
     *  전역 슬롯을 뒤로 밀어 모든 스레드를 함께 멈춘다. */
    private static void penalizeAll(long backoffMs) {
        synchronized (THROTTLE) {
            nextSlotAt = Math.max(nextSlotAt, System.currentTimeMillis() + backoffMs);
        }
    }

    @Qualifier("naverRestClient")
    private final RestClient naverRestClient;

    /** 배치·온디맨드가 공유하는 일일 쿼터 게이트 — 예산 소진 시 외부 호출을 막아 먹통 방지 */
    private final NaverQuotaGate quotaGate;

    /**
     * 장소명으로 블로그 언급 수 조회 (캐시 1시간)
     */
    @Cacheable(value = "blogCount", key = "#placeName")
    public int getBlogCount(String placeName) {
        return fetchBlogCount(placeName, () -> false, () -> {});
    }

    @Cacheable(value = "blogCount", key = "#placeName", condition = "!#stopNaverSearch.get()", unless = "#result == 0")
    public int getBlogCount(String placeName, AtomicBoolean stopNaverSearch) {
        if (stopNaverSearch.get()) {
            log.debug("Naver blog search stopped for current course generation - skipping [{}]", placeName);
            return 0;
        }
        return fetchBlogCount(placeName, stopNaverSearch::get, () -> stopNaverSearch.set(true));
    }

    private int fetchBlogCount(String placeName, BooleanSupplier shouldStop, Runnable onRateLimitExhausted) {
        // 일일 쿼터 게이트 — 예산 소진 시 외부 호출 없이 0점으로 degrade (배치·온디맨드 공유 예산)
        if (!quotaGate.tryAcquire()) {
            log.warn("[네이버쿼터] 일일 예산 소진 — 블로그 조회 건너뜀 [{}]", placeName);
            return 0;
        }
        // 429(rate limit) 시 지수 백오프 재시도 — 병렬 호출 환경에서 일시적 초과 흡수
        for (int attempt = 0; attempt < 4; attempt++) {
            if (shouldStop.getAsBoolean()) {
                return 0;
            }

            try {
                acquireSlot();
                Map<?, ?> body = naverRestClient
                        .get()
                        .uri(ub -> ub.path("/search/blog")
                                .queryParam("query", placeName)
                                .queryParam("display", 1)
                                .build())
                        .retrieve()
                        .body(Map.class);

                if (body == null) return 0;
                Object total = body.get("total");
                return total instanceof Number ? ((Number) total).intValue() : 0;

            } catch (org.springframework.web.client.HttpClientErrorException.TooManyRequests e) {
                long backoff = 250L * (1L << attempt);   // 250·500·1000·2000ms
                penalizeAll(backoff);                    // 전 스레드 공통 정지 — 재시도가 깨끗한 슬롯을 받게
                try { Thread.sleep(backoff); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); return 0; }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return 0;
            } catch (Exception e) {
                log.warn("네이버 블로그 검색 실패 [{}]: {}", placeName, e.getMessage());
                return 0;
            }
        }
        onRateLimitExhausted.run();
        log.warn("네이버 블로그 검색 429 재시도 소진 [{}] - 현재 코스 생성 시도에서 추가 검색 중지", placeName);
        return 0;
    }

    /**
     * 블로그 언급 수 → trendScore (0.0 ~ 100.0)
     *   10,000건 미만 → 0.0
     *   1,000,000건   → 100.0  (로그 스케일)
     */
    public double calcTrendScore(String placeName) {
        int count = getBlogCount(placeName);
        return calcTrendScore(count);
    }

    public double calcTrendScore(int count) {
        if (count < MIN_COUNT) return 0.0;

        double score = (Math.log10(count) / Math.log10(LOG_BASE_COUNT)) * 100.0;
        return Math.min(100.0, Math.max(0.0, score));
    }

    /**
     * 여행 가치가 있는 장소인지 빠르게 판단
     */
    public boolean isWorthVisiting(String placeName) {
        return getBlogCount(placeName) >= MIN_COUNT;
    }
}
