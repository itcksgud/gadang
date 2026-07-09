package com.gadang.security;

import com.gadang.user.User;
import com.gadang.user.UserMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProvider jwtProvider;
    private final UserMapper userMapper;

    public JwtAuthenticationFilter(JwtProvider jwtProvider, UserMapper userMapper) {
        this.jwtProvider = jwtProvider;
        this.userMapper = userMapper;
    }

    /**
     * 토큰이 유효하면 SecurityContext에 인증을 싣고, 무효(만료·위조·탈퇴 유저)면
     * 401을 직접 쓰지 않고 미인증 상태로 체인을 계속 태운다.
     * 보호된 엔드포인트는 SecurityConfig의 AuthenticationEntryPoint가 401을 내고,
     * permitAll 엔드포인트는 익명으로 통과한다 — 만료 토큰이 붙은 /api/auth/refresh
     * 호출도 여기서 죽지 않는다.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws IOException, jakarta.servlet.ServletException {
        String token = resolveToken(request);
        if (token != null) {
            try {
                Long userId = jwtProvider.getUserId(token);
                User user = userMapper.findById(userId);
                if (user != null) {
                    CurrentUser currentUser = new CurrentUser(user.getUserId(), user.getEmail(), user.getNickname(), user.getRole());
                    var authentication = new UsernamePasswordAuthenticationToken(
                            currentUser,
                            null,
                            currentUser.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (Exception e) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        return header.substring(7);
    }
}
