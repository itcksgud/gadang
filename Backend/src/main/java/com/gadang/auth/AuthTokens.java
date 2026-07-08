package com.gadang.auth;

/** 로그인 결과 — body로 나갈 access 토큰 응답 + 쿠키로 나갈 refresh 토큰 */
public record AuthTokens(
        AuthResponse response,
        String refreshToken
) {
}
