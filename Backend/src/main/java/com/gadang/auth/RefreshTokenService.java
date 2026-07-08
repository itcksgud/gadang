package com.gadang.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Refresh 토큰 저장소 (Redis).
 *
 * 토큰은 서명 없는 불투명(opaque) 난수 문자열 — JWT와 달리 서버 저장이 전제라
 * 로그아웃·탈취 시 즉시 무효화할 수 있고, 만료는 Redis EXPIRE에 위임한다.
 * 키: refresh:{token} → 값: userId
 */
@Service
public class RefreshTokenService {

    private static final String KEY_PREFIX = "refresh:";

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(
            StringRedisTemplate redisTemplate,
            @Value("${jwt.refresh-expiration-days:14}") long ttlDays) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofDays(ttlDays);
    }

    /** 새 refresh 토큰 발급 (256bit 난수, URL-safe Base64) */
    public String issue(Long userId) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisTemplate.opsForValue().set(KEY_PREFIX + token, String.valueOf(userId), ttl);
        return token;
    }

    /**
     * 토큰을 1회 소모하고 소유자 userId를 반환 (rotation의 전반부).
     * getAndDelete로 조회+삭제를 원자 처리 — 같은 토큰의 동시 재발급 요청이나
     * 탈취된 토큰의 재사용은 두 번째 호출부터 null(무효)이 된다.
     */
    public Long consume(String token) {
        String userId = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + token);
        return userId == null ? null : Long.parseLong(userId);
    }

    /** 로그아웃 등 명시적 무효화 */
    public void revoke(String token) {
        redisTemplate.delete(KEY_PREFIX + token);
    }

    public Duration ttl() {
        return ttl;
    }
}
