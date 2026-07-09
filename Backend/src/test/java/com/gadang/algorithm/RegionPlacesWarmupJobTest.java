package com.gadang.algorithm;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 장소 캐시 워밍 배치 — 쿼터 예산(하루 10개)과 우선순위(콜드 → 만료 임박 → 신선 건너뜀) 검증.
 */
class RegionPlacesWarmupJobTest {

    private final ScoredPlaceProvider provider = mock(ScoredPlaceProvider.class);
    private final PlaceFilterService filterService = mock(PlaceFilterService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    private RegionPlacesWarmupJob job() {
        return new RegionPlacesWarmupJob(provider, filterService, redis);
    }

    @Test
    void 하루_예산을_넘겨_데우지_않는다() {
        when(filterService.resolveRegionCenter(anyString()))
                .thenReturn(Optional.of(new double[]{35.0, 129.0}));
        when(redis.getExpire(anyString(), any(TimeUnit.class))).thenReturn(-2L); // 전 지역 콜드
        when(provider.getScoredPlaces(anyDouble(), anyDouble(), anyInt(), any(), anyString()))
                .thenReturn(List.of());

        job().warmRegionPlaces();

        verify(provider, atMost(10))
                .getScoredPlaces(anyDouble(), anyDouble(), anyInt(), any(), anyString());
    }

    @Test
    void 신선한_지역은_건너뛰고_콜드를_먼저_데운다() {
        List<String> regions = List.copyOf(RegionSeedData.REGION_META.keySet());
        assertTrue(regions.size() >= 2, "테스트에는 지역이 2개 이상 필요");
        String coldRegion = regions.get(0);
        String staleRegion = regions.get(1);

        when(filterService.resolveRegionCenter(anyString()))
                .thenReturn(Optional.of(new double[]{35.0, 129.0}));
        // 기본: 전 지역 신선(TTL 6일) → 건너뜀
        when(redis.getExpire(anyString(), any(TimeUnit.class)))
                .thenReturn(TimeUnit.DAYS.toSeconds(6));
        // 첫 지역만 콜드, 둘째 지역은 만료 임박(TTL 1시간)
        double[] cold = RealScoredPlaceProviderTestSupport.center(filterService, coldRegion);
        when(redis.getExpire(eq(RealScoredPlaceProvider.gridKey(cold[0], cold[1], null, coldRegion)),
                any(TimeUnit.class))).thenReturn(-2L);
        double[] stale = RealScoredPlaceProviderTestSupport.center(filterService, staleRegion);
        when(redis.getExpire(eq(RealScoredPlaceProvider.gridKey(stale[0], stale[1], null, staleRegion)),
                any(TimeUnit.class))).thenReturn(3600L);
        when(provider.getScoredPlaces(anyDouble(), anyDouble(), anyInt(), any(), anyString()))
                .thenReturn(List.of());

        job().warmRegionPlaces();

        // 콜드 → 만료 임박 순으로 정확히 2개만 데운다
        InOrder inOrder = Mockito.inOrder(provider);
        inOrder.verify(provider).getScoredPlaces(anyDouble(), anyDouble(), anyInt(), any(), eq(coldRegion));
        inOrder.verify(provider).getScoredPlaces(anyDouble(), anyDouble(), anyInt(), any(), eq(staleRegion));
        verify(provider, never()).getScoredPlaces(anyDouble(), anyDouble(), anyInt(), any(), eq(regions.get(2)));
    }

    /** 모킹된 resolveRegionCenter와 동일 좌표를 돌려주는 헬퍼 */
    private static class RealScoredPlaceProviderTestSupport {
        static double[] center(PlaceFilterService fs, String region) {
            return fs.resolveRegionCenter(region).orElseThrow();
        }
    }
}
