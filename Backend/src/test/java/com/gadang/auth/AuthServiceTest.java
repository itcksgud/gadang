package com.gadang.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gadang.common.exception.GadangException;
import com.gadang.security.JwtProvider;
import com.gadang.user.User;
import com.gadang.user.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtProvider jwtProvider;

    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private AuthService authService;

    @Test
    void loginReturnsTokenPairWhenPasswordMatches() {
        User user = user("user@test.com", "encoded", "USER");
        when(userMapper.findByEmail("user@test.com")).thenReturn(user);
        when(passwordEncoder.matches("raw", "encoded")).thenReturn(true);
        when(jwtProvider.createToken(1L, "user@test.com", "USER")).thenReturn("token");
        when(refreshTokenService.issue(1L)).thenReturn("refresh-token");

        AuthTokens tokens = authService.login(new AuthRequest("user@test.com", "raw"));

        assertThat(tokens.response().token()).isEqualTo("token");
        assertThat(tokens.response().user().email()).isEqualTo("user@test.com");
        assertThat(tokens.refreshToken()).isEqualTo("refresh-token");
    }

    @Test
    void signupRejectsDuplicateEmail() {
        when(userMapper.findByEmail("user@test.com")).thenReturn(user("user@test.com", "encoded", "USER"));

        assertThatThrownBy(() -> authService.signup(new SignupRequest("user@test.com", "raw", "tester")))
                .isInstanceOf(GadangException.class)
                .hasMessage("이미 가입된 이메일입니다.");
    }

    @Test
    void signupCreatesUserRoleAccount() {
        when(userMapper.findByEmail("new@test.com")).thenReturn(null);
        when(passwordEncoder.encode("raw")).thenReturn("encoded");
        when(jwtProvider.createToken(1L, "new@test.com", "USER")).thenReturn("token");
        when(userMapper.findById(1L)).thenReturn(user("new@test.com", "encoded", "USER"));
        when(refreshTokenService.issue(1L)).thenReturn("refresh-token");
        org.mockito.Mockito.doAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setUserId(1L);
            return null;
        }).when(userMapper).insert(any(User.class));

        AuthTokens tokens = authService.signup(new SignupRequest("new@test.com", "raw", "newbie"));

        assertThat(tokens.response().token()).isEqualTo("token");
        assertThat(tokens.response().user().role()).isEqualTo("USER");
    }

    @Test
    void refreshRotatesTokenAndIssuesNewAccessToken() {
        when(refreshTokenService.consume("old-refresh")).thenReturn(1L);
        when(userMapper.findById(1L)).thenReturn(user("user@test.com", "encoded", "USER"));
        when(jwtProvider.createToken(1L, "user@test.com", "USER")).thenReturn("new-access");
        when(refreshTokenService.issue(1L)).thenReturn("new-refresh");

        AuthTokens tokens = authService.refresh("old-refresh");

        assertThat(tokens.response().token()).isEqualTo("new-access");
        assertThat(tokens.refreshToken()).isEqualTo("new-refresh");
    }

    @Test
    void refreshRejectsUnknownOrReusedToken() {
        when(refreshTokenService.consume("stolen-or-expired")).thenReturn(null);

        assertThatThrownBy(() -> authService.refresh("stolen-or-expired"))
                .isInstanceOf(GadangException.class)
                .hasMessage("세션이 만료되었습니다. 다시 로그인해 주세요.");
    }

    @Test
    void refreshRejectsMissingCookie() {
        assertThatThrownBy(() -> authService.refresh(null))
                .isInstanceOf(GadangException.class)
                .hasMessage("세션이 만료되었습니다. 다시 로그인해 주세요.");
    }

    @Test
    void logoutRevokesRefreshToken() {
        authService.logout("refresh-token");

        verify(refreshTokenService).revoke("refresh-token");
    }

    private User user(String email, String password, String role) {
        User user = new User();
        user.setUserId(1L);
        user.setEmail(email);
        user.setPassword(password);
        user.setNickname("tester");
        user.setRole(role);
        return user;
    }
}
