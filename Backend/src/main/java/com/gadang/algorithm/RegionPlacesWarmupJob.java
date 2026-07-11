package com.gadang.algorithm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 지역 장소 캐시(지도 탭) 새벽 선워밍 배치.
 *
 * 문제: 콜드 지역 첫 조회는 Kakao 구역 수집 + Naver 블로그 채점(초당 속도 제한)으로
 * 2~3분이 걸린다 (울산 실측 162s / 1,002 places). 프론트 타임아웃(120~180s)을 넘기면
 * 지도가 빈 채로 뜬다. 사용자가 콜드 경로를 밟지 않도록 배치가 미리 데운다.
 *
 * 쿼터 예산: 콜드 지역 1곳 ≈ 장소 수만큼 Naver 블로그 호출(최대 ~1,000건).
 * REGIONS_PER_RUN=18 → 일 최대 ~18,000건으로 Naver 일 쿼터(25,000)의 ~72% 상한.
 * 평소엔 TTL(30일)이 길어 만료 임박 지역만 소수 갱신 → 실제 사용량은 훨씬 적다
 * (비인기 지역은 장소 수도 적어 호출도 적음). 상한을 높인 건 Redis 유실 시
 * 전체 49개 지역을 빠르게(~3일) 재워밍하기 위한 여유다.
 *
 * 우선순위: ① Redis에 키가 없는 지역(콜드) ② 남은 TTL이 짧은 순(만료 임박).
 * TTL 3일 이상 남은 지역은 건너뛴다 — 저장 TTL 30일 기준 약 한 달마다 갱신되는 셈.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RegionPlacesWarmupJob {

    private final ScoredPlaceProvider scoredPlaceProvider;   // @Primary Real — L1+L2 캐시 경유
    private final PlaceFilterService placeFilterService;
    private final StringRedisTemplate redisTemplate;

    /** 하루에 데울 최대 지역 수 — Naver 블로그 쿼터 예산 상한(~72%) */
    private static final int REGIONS_PER_RUN = 18;
    /** 남은 TTL이 이보다 길면 아직 신선 — 건너뜀 (TTL 30일 기준 약 한 달 주기 갱신) */
    private static final long REFRESH_UNDER_TTL_SECONDS = TimeUnit.DAYS.toSeconds(3);
    /** 지도 탭 기본 반경과 동일 — 캐시 키(격자)가 실사용 경로와 일치해야 의미가 있다 */
    private static final int WARMUP_RADIUS_METERS = 5_000;

    /** 매일 05:00 — 교통 배치(04:00) 뒤 시간대 분리 */
    @Scheduled(cron = "0 0 5 * * *")
    public void warmRegionPlaces() {
        List<Target> targets = collectTargets();
        if (targets.isEmpty()) {
            log.info("[배치/장소워밍] 모든 지역 캐시가 신선함 — 건너뜀");
            return;
        }

        // 콜드 우선, 그다음 만료 임박 순. 순차 실행 — Naver 속도 제한을 실사용과 나눠 쓰지 않도록.
        targets.sort(Comparator.comparingLong(Target::ttlSeconds));
        int budget = Math.min(REGIONS_PER_RUN, targets.size());
        int ok = 0, fail = 0;
        long startMs = System.currentTimeMillis();

        for (int i = 0; i < budget; i++) {
            Target t = targets.get(i);
            try {
                List<PlaceCandidate> places = scoredPlaceProvider.getScoredPlaces(
                        t.lat(), t.lng(), WARMUP_RADIUS_METERS, null, t.region());
                ok++;
                log.info("[배치/장소워밍] {} — {} places ({})", t.region(), places.size(),
                        t.ttlSeconds() == COLD ? "콜드" : "TTL " + t.ttlSeconds() / 3600 + "h 남음");
            } catch (Exception e) {
                fail++;
                log.warn("[배치/장소워밍] {} 실패: {}", t.region(), e.getMessage());
            }
        }
        log.info("[배치/장소워밍] 완료 — 대상 {} / 처리 {} / 성공 {} / 실패 {} / {}초",
                targets.size(), budget, ok, fail, (System.currentTimeMillis() - startMs) / 1000);
    }

    private static final long COLD = -1;

    private List<Target> collectTargets() {
        List<Target> targets = new ArrayList<>();
        for (String region : RegionSeedData.REGION_META.keySet()) {
            double[] center = placeFilterService.resolveRegionCenter(region).orElse(null);
            if (center == null) continue;

            String key = RealScoredPlaceProvider.gridKey(center[0], center[1], null, region);
            Long expire = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            if (expire == null || expire == -2) {                       // 키 없음 = 콜드
                targets.add(new Target(region, center[0], center[1], COLD));
            } else if (expire >= 0 && expire < REFRESH_UNDER_TTL_SECONDS) { // 만료 임박
                targets.add(new Target(region, center[0], center[1], expire));
            }
        }
        return targets;
    }

    private record Target(String region, double lat, double lng, long ttlSeconds) {}
}
