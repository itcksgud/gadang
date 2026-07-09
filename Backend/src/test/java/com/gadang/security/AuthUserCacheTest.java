package com.gadang.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gadang.user.User;
import com.gadang.user.UserMapper;
import org.junit.jupiter.api.Test;

/**
 * JWT 필터 유저 캐시 — 반복 요청이 DB를 반복 조회하지 않고,
 * 탈퇴(evict) 시 다음 요청부터 즉시 반영되는지 검증.
 */
class AuthUserCacheTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final AuthUserCache cache = new AuthUserCache(userMapper);

    @Test
    void 같은_유저_반복_조회는_DB를_한_번만_친다() {
        User user = new User();
        user.setUserId(1L);
        when(userMapper.findById(1L)).thenReturn(user);

        assertThat(cache.find(1L)).isSameAs(user);
        assertThat(cache.find(1L)).isSameAs(user);
        assertThat(cache.find(1L)).isSameAs(user);

        verify(userMapper, times(1)).findById(1L);
    }

    @Test
    void 탈퇴한_유저도_캐시돼_반복_DB_조회를_막는다() {
        when(userMapper.findById(2L)).thenReturn(null);

        assertThat(cache.find(2L)).isNull();
        assertThat(cache.find(2L)).isNull();

        verify(userMapper, times(1)).findById(2L);
    }

    @Test
    void evict_후에는_다음_조회가_DB를_다시_쳐서_즉시_반영된다() {
        User user = new User();
        user.setUserId(3L);
        when(userMapper.findById(3L)).thenReturn(user).thenReturn(null); // 조회 → 탈퇴

        assertThat(cache.find(3L)).isSameAs(user);
        cache.evict(3L);                       // 탈퇴 처리 시 호출되는 경로
        assertThat(cache.find(3L)).isNull();   // 60초 TTL을 기다리지 않고 즉시 차단

        verify(userMapper, times(2)).findById(3L);
    }
}
