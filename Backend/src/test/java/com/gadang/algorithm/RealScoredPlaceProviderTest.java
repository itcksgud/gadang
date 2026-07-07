package com.gadang.algorithm;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealScoredPlaceProviderTest {

    private final PlaceFilterService placeFilterService = mock(PlaceFilterService.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final RealScoredPlaceProvider provider;

    RealScoredPlaceProviderTest() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        provider = new RealScoredPlaceProvider(placeFilterService, redisTemplate);
    }

    @Test
    void emptyRedisCacheIsIgnoredAndRebuilt() {
        PlaceCandidate candidate = PlaceCandidate.builder()
                .name("부산타워")
                .categoryCode("AT4")
                .finalScore(80)
                .build();
        when(valueOps.get(anyString())).thenReturn("[]");
        when(placeFilterService.filterByCoord(anyDouble(), anyDouble(), anyInt(), any(), eq("부산")))
                .thenReturn(List.of(candidate));

        List<PlaceCandidate> result = provider.getScoredPlaces(35.1152, 129.0415, 20_000, List.of("AT4"), "부산");

        assertThat(result).containsExactly(candidate);
        verify(placeFilterService).filterByCoord(anyDouble(), anyDouble(), anyInt(), any(), eq("부산"));
        verify(valueOps).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void emptyLiveResultIsNotSavedToRedisCache() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(placeFilterService.filterByCoord(anyDouble(), anyDouble(), anyInt(), any(), eq("부산")))
                .thenReturn(List.of());

        List<PlaceCandidate> result = provider.getScoredPlaces(35.1152, 129.0415, 20_000, List.of("AT4"), "부산");

        assertThat(result).isEmpty();
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }
}

