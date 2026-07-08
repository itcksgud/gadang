package com.gadang.auth;

import com.gadang.common.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;
    private final boolean cookieSecure;

    public AuthController(AuthService authService,
                          RefreshTokenService refreshTokenService,
                          @Value("${gadang.cookie.secure:false}") boolean cookieSecure) {
        this.authService = authService;
        this.refreshTokenService = refreshTokenService;
        this.cookieSecure = cookieSecure;
    }

    @PostMapping("/signup")
    public ApiResponse<AuthResponse> signup(@RequestBody SignupRequest request, HttpServletResponse response) {
        return respondWithTokens("회원가입이 완료되었습니다.", authService.signup(request), response);
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@RequestBody AuthRequest request, HttpServletResponse response) {
        return respondWithTokens("로그인되었습니다.", authService.login(request), response);
    }

    /** access 토큰 만료 시 refresh 쿠키로 재발급 — 매번 refresh 토큰도 교체(rotation) */
    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(
            @CookieValue(value = RefreshCookie.NAME, required = false) String refreshToken,
            HttpServletResponse response) {
        return respondWithTokens("토큰이 재발급되었습니다.", authService.refresh(refreshToken), response);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @CookieValue(value = RefreshCookie.NAME, required = false) String refreshToken,
            HttpServletResponse response) {
        authService.logout(refreshToken);
        response.addHeader(HttpHeaders.SET_COOKIE, RefreshCookie.expired(cookieSecure).toString());
        return ApiResponse.ok("로그아웃되었습니다.", null);
    }

    private ApiResponse<AuthResponse> respondWithTokens(
            String message, AuthTokens tokens, HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                RefreshCookie.of(tokens.refreshToken(), refreshTokenService.ttl(), cookieSecure).toString());
        return ApiResponse.ok(message, tokens.response());
    }
}
