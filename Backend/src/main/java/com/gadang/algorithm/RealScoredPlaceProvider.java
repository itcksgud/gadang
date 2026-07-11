package com.gadang.algorithm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class RealScoredPlaceProvider implements ScoredPlaceProvider, CourseCandidateProvider {

    private final PlaceFilterService placeFilterService;
    private final StringRedisTemplate redisTemplate;   // L2 공유 캐시 (기존 REGION_PLACES DB 테이블 대체)
    private final ObjectMapper objectMapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final double GRID = 0.05;
    private static final Duration TTL = Duration.ofDays(30);  // Redis EXPIRE가 만료 자동 처리 (한 달 — 배치가 만료 전 갱신)
    private static final String CACHE_VERSION = "v10";

    @Override
    @Cacheable(value = "placeCandidates", key = "T(com.gadang.algorithm.RealScoredPlaceProvider).gridKey(#lat, #lng, #categories, null)", sync = true)
    public List<PlaceCandidate> getScoredPlaces(double lat, double lng,
                                                int radiusMeters, List<String> categories) {
        return getScoredPlaces(lat, lng, radiusMeters, categories, null);
    }

    @Override
    @Cacheable(value = "placeCandidates", key = "T(com.gadang.algorithm.RealScoredPlaceProvider).gridKey(#lat, #lng, #categories, #regionHint)", sync = true)
    public List<PlaceCandidate> getScoredPlaces(double lat, double lng,
                                                int radiusMeters, List<String> categories,
                                                String regionHint) {
        String key = gridKey(lat, lng, categories, regionHint);

        List<PlaceCandidate> cached = loadCachedPlaces(key);
        if (cached != null) {
            log.info("[Place/L2] {} redis hit ({} places)", key, cached.size());
            return cached;
        }

        if (categories != null && !categories.isEmpty()) {
            String allKey = gridKey(lat, lng, null, regionHint);
            List<PlaceCandidate> allCached = loadCachedPlaces(allKey);
            if (allCached != null) {
                List<PlaceCandidate> filtered = filterCategories(allCached, categories);
                log.info("[Place/L2] {} redis hit via ALL cache {} ({} places)",
                        key, allKey, filtered.size());
                return filtered;
            }
        }

        return buildAndSavePlaces(key, lat, lng, radiusMeters, categories, regionHint);
    }

    @Override
    public List<PlaceCandidate> getCourseCandidates(double lat, double lng,
                                                    int radiusMeters, List<String> categories,
                                                    String regionHint) {
        String key = gridKey(lat, lng, categories, regionHint);

        List<PlaceCandidate> cached = loadCachedPlaces(key);
        if (cached != null) {
            log.info("[Course/Place/L2] {} redis hit ({} places)", key, cached.size());
            return cached;
        }

        String allKey = gridKey(lat, lng, null, regionHint);
        List<PlaceCandidate> allCached = loadCachedPlaces(allKey);
        if (allCached == null) {
            log.info("[Course/Place/L2] {} redis miss; preparing map place cache {}", key, allKey);
            buildAndSavePlaces(allKey, lat, lng, radiusMeters, null, regionHint);
            allCached = loadCachedPlaces(allKey);
        }

        if (allCached == null) {
            log.warn("[Course/Place/L2] {} unavailable after map cache preparation; course will use no live place scoring", allKey);
            return List.of();
        }

        List<PlaceCandidate> result = filterCategories(allCached, categories);
        log.info("[Course/Place/L2] {} loaded from ALL cache {} ({} places)",
                key, allKey, result.size());
        return result;
    }

    @Override
    public void streamScoredPlaces(double lat, double lng, int radiusMeters,
                                   List<String> categories, String regionHint,
                                   java.util.function.Consumer<List<PlaceCandidate>> onPartial,
                                   java.util.function.Consumer<List<PlaceCandidate>> onComplete) {
        String key = gridKey(lat, lng, categories, regionHint);

        List<PlaceCandidate> cached = loadCachedPlaces(key);
        if (cached != null) {
            log.info("[Place/L2/stream] {} redis hit ({} places)", key, cached.size());
            onComplete.accept(cached);
            return;
        }

        List<PlaceCandidate> result =
                placeFilterService.filterByCoord(lat, lng, radiusMeters, categories, regionHint, onPartial);
        if (result != null && !result.isEmpty()) {
            savePlaces(key, result);
        } else {
            log.warn("[Place/L2/stream] skip empty save {}", key);
        }
        onComplete.accept(result);
    }

    private List<PlaceCandidate> buildAndSavePlaces(String key, double lat, double lng,
                                                    int radiusMeters, List<String> categories,
                                                    String regionHint) {
        List<PlaceCandidate> result = placeFilterService.filterByCoord(lat, lng, radiusMeters, categories, regionHint);

        if (result == null || result.isEmpty()) {
            log.warn("[Place/L2] skip empty save {}", key);
            return List.of();
        }

        savePlaces(key, result);
        return result;
    }

    /** Redis에 JSON으로 저장 — TTL은 Redis EXPIRE에 위임 (기존 DB의 updated_at 수동 검사 대체) */
    private void savePlaces(String key, List<PlaceCandidate> result) {
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(result), TTL);
        } catch (Exception e) {
            log.warn("[Place/L2] redis save failed {}: {}", key, e.getMessage());
        }
    }

    private List<PlaceCandidate> loadCachedPlaces(String key) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return null;
            List<PlaceCandidate> cached = objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, PlaceCandidate.class));
            if (cached == null || cached.isEmpty()) {
                log.info("[Place/L2] {} empty cache ignored", key);
                return null;
            }
            return cached;
        } catch (Exception e) {
            log.warn("[Place/L2] restore failed {}: {}", key, e.getMessage());
            return null;
        }
    }

    private List<PlaceCandidate> filterCategories(List<PlaceCandidate> candidates, List<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return candidates;
        }
        Set<String> allowed = new LinkedHashSet<>(categories);
        return candidates.stream()
                .filter(candidate -> allowed.contains(candidate.getCategoryCode()))
                .toList();
    }

    public static String gridKey(double lat, double lng, List<String> categories, String regionHint) {
        long glat = Math.round(lat / GRID);
        long glng = Math.round(lng / GRID);
        String cats = (categories == null || categories.isEmpty())
                ? "ALL"
                : categories.stream().sorted().collect(Collectors.joining(","));
        String hint = (regionHint == null || regionHint.isBlank()) ? "" : ":" + regionHint;
        return String.format("places:%s:%d,%d:%s%s", CACHE_VERSION, glat, glng, cats, hint);
    }
}
