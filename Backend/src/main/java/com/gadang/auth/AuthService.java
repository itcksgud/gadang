package com.gadang.auth;

import com.gadang.common.exception.GadangException;
import com.gadang.security.JwtProvider;
import com.gadang.user.User;
import com.gadang.user.UserMapper;
import com.gadang.user.UserSummaryResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final RefreshTokenService refreshTokenService;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder,
                       JwtProvider jwtProvider, RefreshTokenService refreshTokenService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
        this.refreshTokenService = refreshTokenService;
    }

    @Transactional
    public AuthTokens signup(SignupRequest request) {
        String email = requireText(request.email(), "이메일을 입력해 주세요.");
        String password = requireText(request.password(), "비밀번호를 입력해 주세요.");
        String nickname = requireText(request.nickname(), "닉네임을 입력해 주세요.");
        if (userMapper.findByEmail(email) != null) {
            throw GadangException.badRequest("이미 가입된 이메일입니다.");
        }

        User user = new User();
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname);
        user.setRole("USER");
        user.setProvider("local");
        userMapper.insert(user);

        return issueTokens(userMapper.findById(user.getUserId()));
    }

    public AuthTokens login(AuthRequest request) {
        User user = userMapper.findByEmail(requireText(request.email(), "이메일을 입력해 주세요."));
        if (user == null || !passwordEncoder.matches(requireText(request.password(), "비밀번호를 입력해 주세요."), user.getPassword())) {
            throw GadangException.badRequest("이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        return issueTokens(user);
    }

    /**
     * Refresh 토큰 rotation: 쿠키의 토큰을 소모하고 access+refresh 한 쌍을 새로 발급.
     * 소모(consume)가 원자적이라 이미 쓰인(=탈취 후 재사용된) 토큰은 여기서 걸러진다.
     */
    public AuthTokens refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw GadangException.unauthorized("세션이 만료되었습니다. 다시 로그인해 주세요.");
        }
        Long userId = refreshTokenService.consume(refreshToken);
        if (userId == null) {
            throw GadangException.unauthorized("세션이 만료되었습니다. 다시 로그인해 주세요.");
        }
        User user = userMapper.findById(userId);
        if (user == null) {
            throw GadangException.unauthorized("세션이 만료되었습니다. 다시 로그인해 주세요.");
        }
        return issueTokens(user);
    }

    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenService.revoke(refreshToken);
        }
    }

    private AuthTokens issueTokens(User user) {
        String accessToken = jwtProvider.createToken(user.getUserId(), user.getEmail(), user.getRole());
        String refreshToken = refreshTokenService.issue(user.getUserId());
        return new AuthTokens(new AuthResponse(accessToken, UserSummaryResponse.from(user)), refreshToken);
    }

    private String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw GadangException.badRequest(message);
        }
        return value.trim();
    }
}
