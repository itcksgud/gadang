package com.gadang.auth;

import com.gadang.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 소셜 로그인 코드 교환 API.
 *
 * 흐름:
 *   1. 프론트 → 각 소셜 OAuth 로그인 페이지 (직접 리다이렉트)
 *   2. 소셜 → 프론트 /auth/{provider}/callback?code=...
 *   3. 프론트 → POST /api/auth/{provider}/exchange { code, redirectUri }
 *   4. 백엔드 → 토큰 교환 → 사용자 찾기/생성 → access JWT(body) + refresh(HttpOnly 쿠키) 반환
 */
@RestController
@RequestMapping("/api/auth")
public class OAuthController {

    private final OAuthService oAuthService;
    private final RefreshTokenService refreshTokenService;
    private final boolean cookieSecure;

    public OAuthController(OAuthService oAuthService,
                           RefreshTokenService refreshTokenService,
                           @Value("${gadang.cookie.secure:false}") boolean cookieSecure) {
        this.oAuthService = oAuthService;
        this.refreshTokenService = refreshTokenService;
        this.cookieSecure = cookieSecure;
    }

    @PostMapping("/naver/exchange")
    public ApiResponse<AuthResponse> naverExchange(@RequestBody Map<String, String> body,
                                                   HttpServletResponse response) {
        AuthTokens tokens = oAuthService.loginWithNaver(body.get("code"), body.get("redirectUri"));
        return respondWithTokens("네이버 로그인 성공", tokens, response);
    }

    @PostMapping("/kakao/exchange")
    public ApiResponse<AuthResponse> kakaoExchange(@RequestBody Map<String, String> body,
                                                   HttpServletResponse response) {
        AuthTokens tokens = oAuthService.loginWithKakao(body.get("code"), body.get("redirectUri"));
        return respondWithTokens("카카오 로그인 성공", tokens, response);
    }

    private ApiResponse<AuthResponse> respondWithTokens(
            String message, AuthTokens tokens, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                RefreshCookie.of(tokens.refreshToken(), refreshTokenService.ttl(), cookieSecure).toString());
        return ApiResponse.ok(message, tokens.response());
    }
}
