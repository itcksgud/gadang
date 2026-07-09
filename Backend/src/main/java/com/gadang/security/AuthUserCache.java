package com.gadang.security;

import com.gadang.user.User;
import com.gadang.user.UserMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * JWT 필터 전용 유저 조회 캐시.
 *
 * 문제: 필터가 요청마다 userMapper.findById를 치면 인증된 요청 수 = users SELECT 수 —
 * stateless JWT의 확장성 이점을 DB 왕복으로 되사는 꼴이고, DB 장애가 전체 401로 번진다.
 *
 * 해결: 60초 캐시로 쿼리를 흡수하되, 탈퇴·권한 변경 시 evict로 즉시 무효화.
 * 삭제된 유저(empty)도 캐시해 탈퇴 유저 토큰의 반복 DB 조회를 차단한다.
 *
 * 한계(문서화): evict는 인스턴스 로컬이다. 수평 확장 시 다른 인스턴스는 최대 60초
 * 이전 상태를 볼 수 있다 — 즉시성이 필요해지면 Redis pub/sub 브로드캐스트로 확장.
 */
@Component
@RequiredArgsConstructor
public class AuthUserCache {

    private final UserMapper userMapper;

    private final Cache<Long, Optional<User>> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    /** 캐시 경유 조회 — 없는 유저(탈퇴)는 null */
    public User find(Long userId) {
        return cache.get(userId, id -> Optional.ofNullable(userMapper.findById(id))).orElse(null);
    }

    /** 탈퇴·차단·권한 변경 시 호출 — 다음 요청부터 즉시 반영 */
    public void evict(Long userId) {
        cache.invalidate(userId);
    }
}
