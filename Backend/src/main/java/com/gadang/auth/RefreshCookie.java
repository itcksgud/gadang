package com.gadang.auth;

import java.time.Duration;
import org.springframework.http.ResponseCookie;

/**
 * Refresh 토큰 쿠키 생성 유틸.
 *
 * HttpOnly — JS에서 접근 불가(XSS로 refresh 토큰 탈취 차단).
 * Path=/api/auth — 재발급·로그아웃 요청에만 실려 가고 일반 API에는 안 붙는다.
 * SameSite=Lax — 타 사이트발 요청에 쿠키가 실리는 CSRF 기본 방어.
 */
public final class RefreshCookie {

    public static final String NAME = "gadang_refresh";
    private static final String PATH = "/api/auth";

    private RefreshCookie() {
    }

    public static ResponseCookie of(String token, Duration ttl, boolean secure) {
        return ResponseCookie.from(NAME, token)
                .httpOnly(true)
                .secure(secure)
                .path(PATH)
                .maxAge(ttl)
                .sameSite("Lax")
                .build();
    }

    public static ResponseCookie expired(boolean secure) {
        return ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .secure(secure)
                .path(PATH)
                .maxAge(0)
                .sameSite("Lax")
                .build();
    }
}
