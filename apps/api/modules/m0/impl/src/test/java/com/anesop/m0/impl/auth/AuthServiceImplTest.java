package com.anesop.m0.impl.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.auth.model.ChangePasswordRequest;
import com.anesop.contracts.auth.model.CurrentUser;
import com.anesop.contracts.auth.model.DataScope;
import com.anesop.contracts.auth.model.LoginRequest;
import com.anesop.contracts.auth.model.RefreshRequest;
import com.anesop.contracts.auth.model.Session;
import com.anesop.contracts.auth.model.SessionPage;
import com.anesop.contracts.auth.model.TokenResponse;
import com.anesop.m0.api.security.AccessTokenIssuer;
import com.anesop.m0.api.security.AccessTokenRevocationStore;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.AuthUser;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m0.impl.persistence.entity.SysRefreshToken;
import com.anesop.m0.impl.persistence.entity.SysUser;
import com.anesop.m0.impl.persistence.mapper.SysPermissionMapper;
import com.anesop.m0.impl.persistence.mapper.SysRefreshTokenMapper;
import com.anesop.m0.impl.persistence.mapper.SysRoleMapper;
import com.anesop.m0.impl.persistence.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 认证服务单测（M2-M0-03）：登录失败/锁定/停用/非本地认证、刷新轮换、登出、改密、
 * 当前用户与会话分页的异常与边界分支。
 *
 * <p>只 mock 外部协作者（Mapper/编码器/签发器），不 mock 被测对象 {@link AuthServiceImpl}。
 */
class AuthServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    private SysUserMapper userMapper;
    private SysRoleMapper roleMapper;
    private SysPermissionMapper permissionMapper;
    private SysRefreshTokenMapper refreshTokenMapper;
    private PasswordEncoder passwordEncoder;
    private AccessTokenIssuer tokenIssuer;
    private AccessTokenRevocationStore revocationStore;
    private DataScopeResolver dataScopeResolver;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        roleMapper = mock(SysRoleMapper.class);
        permissionMapper = mock(SysPermissionMapper.class);
        refreshTokenMapper = mock(SysRefreshTokenMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        tokenIssuer = mock(AccessTokenIssuer.class);
        revocationStore = mock(AccessTokenRevocationStore.class);
        dataScopeResolver = mock(DataScopeResolver.class);

        when(roleMapper.selectCodesByUserId(ANY_UUID())).thenReturn(List.of("SYS_ADMIN"));
        when(permissionMapper.selectCodesByUserId(ANY_UUID())).thenReturn(List.of("m0:auth:read"));
        when(dataScopeResolver.resolve(ANY_UUID()))
                .thenReturn(DataScopeAssignments.of(List.of(new DataScopeAssignment(
                        com.anesop.m0.api.security.DataScope.ALL, List.of()))));
        when(tokenIssuer.issue(any(AuthUser.class), anyString()))
                .thenReturn(new AccessTokenIssuer.IssuedAccessToken(
                        "SYNTH-ACCESS", "SYNTH-JTI", NOW.plusSeconds(900)));

        service = new AuthServiceImpl(userMapper, roleMapper, permissionMapper, refreshTokenMapper,
                passwordEncoder, tokenIssuer, revocationStore, dataScopeResolver,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), "P7D");
    }

    private static UUID ANY_UUID() {
        return any(UUID.class);
    }

    private static SysUser activeUser() {
        SysUser user = new SysUser();
        user.setId(USER_ID);
        user.setUsername("synth_admin");
        user.setDisplayName("演示管理员（合成）");
        user.setPasswordHash("$2y$10$synthetic");
        user.setStatus(1);
        user.setMustChangePassword(false);
        user.setLockedUntil(null);
        user.setFailedAttempts(0);
        return user;
    }

    private static LoginRequest loginRequest(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        request.setAuthType(LoginRequest.AuthTypeEnum.LOCAL);
        return request;
    }

    @Test
    void loginUnknownAccountUsesDummyHashAndFails() {
        when(userMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.login(loginRequest("ghost", "irrelevant"),
                AuthRequestContext.empty()))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("账号或口令错误");
        verify(passwordEncoder).matches(eq("irrelevant"), anyString());
    }

    @Test
    void loginRejectsDisabledAndUnknownStatus() {
        SysUser disabled = activeUser();
        disabled.setStatus(0);
        when(userMapper.selectOne(any())).thenReturn(disabled);

        assertThatThrownBy(() -> service.login(loginRequest("synth_admin", "x"), null))
                .isInstanceOf(DisabledException.class)
                .hasMessage("账号状态或认证方式不允许登录");

        SysUser unknownStatus = activeUser();
        unknownStatus.setStatus(null);
        when(userMapper.selectOne(any())).thenReturn(unknownStatus);
        assertThatThrownBy(() -> service.login(loginRequest("synth_admin", "x"), null))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    void loginRejectsActiveLock() {
        SysUser locked = activeUser();
        locked.setLockedUntil(NOW.plusSeconds(60));
        when(userMapper.selectOne(any())).thenReturn(locked);

        assertThatThrownBy(() -> service.login(loginRequest("synth_admin", "x"), null))
                .isInstanceOf(LockedException.class)
                .hasMessage("账号状态或认证方式不允许登录");
    }

    @Test
    void loginClearsExpiredLockBeforeCheckingPassword() {
        SysUser expired = activeUser();
        expired.setLockedUntil(NOW.minusSeconds(60));
        when(userMapper.selectOne(any())).thenReturn(expired);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        TokenResponse response = service.login(loginRequest("synth_admin", "good"), null);

        verify(userMapper).resetExpiredLock(USER_ID);
        assertThat(response.getAccessToken()).isEqualTo("SYNTH-ACCESS");
    }

    @Test
    void loginRejectsNonLocalAuthType() {
        SysUser user = activeUser();
        when(userMapper.selectOne(any())).thenReturn(user);
        LoginRequest request = loginRequest("synth_admin", "good");
        request.setAuthType(LoginRequest.AuthTypeEnum.SSO);

        assertThatThrownBy(() -> service.login(request, null))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    void loginWrongPasswordRegistersFailedAttempt() {
        when(userMapper.selectOne(any())).thenReturn(activeUser());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.login(loginRequest("synth_admin", "bad"), null))
                .isInstanceOf(BadCredentialsException.class);
        verify(userMapper).registerFailedAttempt(USER_ID);
    }

    @Test
    void loginSuccessBuildsTokenResponseWithAllDataScopes() {
        SysUser user = activeUser();
        user.setMustChangePassword(null);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(dataScopeResolver.resolve(USER_ID)).thenReturn(DataScopeAssignments.of(List.of(
                new DataScopeAssignment(com.anesop.m0.api.security.DataScope.SITE, List.of("site-1")),
                new DataScopeAssignment(com.anesop.m0.api.security.DataScope.DEPT, List.of("dept-1")),
                new DataScopeAssignment(com.anesop.m0.api.security.DataScope.ROOM, List.of("room-1")),
                new DataScopeAssignment(com.anesop.m0.api.security.DataScope.SELF, List.of()))));

        TokenResponse response = service.login(loginRequest(" synth_admin ", "good"),
                new AuthRequestContext("10.0.0.1", "JUnit", "trace-1"));

        assertThat(response.getTokenType()).isEqualTo(TokenResponse.TokenTypeEnum.BEARER);
        assertThat(response.getExpiresIn()).isBetween(898, 900);
        CurrentUser currentUser = response.getUser();
        assertThat(currentUser.getUsername()).isEqualTo("synth_admin");
        assertThat(currentUser.getMustChangePassword()).as("null 视为强制改密").isTrue();
        assertThat(currentUser.getSiteIds()).containsExactly("site-1");
        assertThat(currentUser.getDataScopes())
                .extracting(DataScope::getType)
                .containsExactly(DataScope.TypeEnum.SITE, DataScope.TypeEnum.DEPARTMENT,
                        DataScope.TypeEnum.ROOM, DataScope.TypeEnum.OWN_PATIENTS);
        verify(userMapper).update(any(), any(Wrapper.class));
    }

    @Test
    void loginTruncatesOversizedClientInfo() {
        when(userMapper.selectOne(any())).thenReturn(activeUser());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        String longAgent = "A".repeat(800);

        service.login(loginRequest("synth_admin", "good"),
                new AuthRequestContext(null, longAgent, null));

        verify(refreshTokenMapper).insert(any(SysRefreshToken.class));
    }

    @Test
    void refreshRejectsMissingRevokedAndExpiredTokens() {
        when(refreshTokenMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> service.refresh(refreshRequest("token"), null))
                .isInstanceOf(BadCredentialsException.class);

        SysRefreshToken revoked = refreshRow();
        revoked.setRevokedAt(NOW.minusSeconds(1));
        when(refreshTokenMapper.selectOne(any(Wrapper.class))).thenReturn(revoked);
        assertThatThrownBy(() -> service.refresh(refreshRequest("token"), null))
                .isInstanceOf(BadCredentialsException.class);

        SysRefreshToken expired = refreshRow();
        expired.setExpiresAt(NOW.minusSeconds(1));
        when(refreshTokenMapper.selectOne(any(Wrapper.class))).thenReturn(expired);
        assertThatThrownBy(() -> service.refresh(refreshRequest("token"), null))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("无效或已过期");
    }

    @Test
    void refreshRejectsConcurrentReuseWhenConditionalRevokeLoses() {
        when(refreshTokenMapper.selectOne(any(Wrapper.class))).thenReturn(refreshRow());
        when(refreshTokenMapper.update(any(), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.refresh(refreshRequest("token"), null))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("已被使用");
    }

    @Test
    void refreshRejectsUnavailableUser() {
        when(refreshTokenMapper.selectOne(any(Wrapper.class))).thenReturn(refreshRow());
        when(refreshTokenMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(userMapper.selectById(USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.refresh(refreshRequest("token"), null))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("账号不可用");

        SysUser disabled = activeUser();
        disabled.setStatus(0);
        when(userMapper.selectById(USER_ID)).thenReturn(disabled);
        assertThatThrownBy(() -> service.refresh(refreshRequest("token"), null))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void refreshRotatesTokenOnSuccess() {
        when(refreshTokenMapper.selectOne(any(Wrapper.class))).thenReturn(refreshRow());
        when(refreshTokenMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(userMapper.selectById(USER_ID)).thenReturn(activeUser());

        TokenResponse response = service.refresh(refreshRequest("token"), null);

        assertThat(response.getRefreshToken()).isNotEqualTo("token");
        assertThat(response.getAccessToken()).isEqualTo("SYNTH-ACCESS");
    }

    @Test
    void logoutRevokesSessionWhenSessionIdIsValidOrMalformed() {
        AuthPrincipal principal = new AuthPrincipal(USER_ID, "synth_admin", "jti-1",
                NOW.plusSeconds(900), "not-a-uuid");

        service.logout(principal, null);

        verify(revocationStore).revoke("jti-1", NOW.plusSeconds(900));
        verify(refreshTokenMapper).update(any(), any(Wrapper.class));

        AuthPrincipal noSession = new AuthPrincipal(USER_ID, "synth_admin", "jti-2",
                NOW.plusSeconds(900), null);
        service.logout(noSession, null);
        verify(revocationStore).revoke("jti-2", NOW.plusSeconds(900));
    }

    @Test
    void changePasswordValidatesUserOldPasswordAndPolicy() {
        when(userMapper.selectById(USER_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.changePassword(principal(), changeRequest("old", newPassword()), null))
                .isInstanceOf(BadCredentialsException.class);

        SysUser disabled = activeUser();
        disabled.setStatus(0);
        when(userMapper.selectById(USER_ID)).thenReturn(disabled);
        assertThatThrownBy(() -> service.changePassword(principal(), changeRequest("old", newPassword()), null))
                .isInstanceOf(BadCredentialsException.class);

        when(userMapper.selectById(USER_ID)).thenReturn(activeUser());
        when(passwordEncoder.matches(eq("wrong"), anyString())).thenReturn(false);
        assertThatThrownBy(() -> service.changePassword(principal(), changeRequest("wrong", newPassword()), null))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("旧口令错误");

        when(passwordEncoder.matches(eq("old"), anyString())).thenReturn(true);
        assertThatThrownBy(() -> service.changePassword(principal(), changeRequest("old", "short"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("长度");
    }

    @Test
    void changePasswordFailsWhenNothingUpdatedAndSucceedsOtherwise() {
        when(userMapper.selectById(USER_ID)).thenReturn(activeUser());
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("$2y$10$new");
        when(userMapper.updatePassword(eq(USER_ID), anyString())).thenReturn(0);

        assertThatThrownBy(() -> service.changePassword(principal(), changeRequest("old", newPassword()), null))
                .isInstanceOf(BadCredentialsException.class);

        when(userMapper.updatePassword(eq(USER_ID), anyString())).thenReturn(1);
        service.changePassword(principal(), changeRequest("old", newPassword()), null);
        verify(userMapper, times(2)).updatePassword(USER_ID, "$2y$10$new");
    }

    @Test
    void currentUserFailsClosedForUnknownOrDisabledUser() {
        when(userMapper.selectById(USER_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.currentUser(USER_ID))
                .isInstanceOf(BadCredentialsException.class);

        SysUser disabled = activeUser();
        disabled.setStatus(0);
        when(userMapper.selectById(USER_ID)).thenReturn(disabled);
        assertThatThrownBy(() -> service.currentUser(USER_ID))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void currentUserOmitsNoneScopeMarker() {
        when(userMapper.selectById(USER_ID)).thenReturn(activeUser());
        when(dataScopeResolver.resolve(USER_ID)).thenReturn(DataScopeAssignments.denied());

        CurrentUser user = service.currentUser(USER_ID);

        assertThat(user.getDataScopes()).isEmpty();
        assertThat(user.getSiteIds()).isEmpty();
    }

    @Test
    void sessionsClampsPagingAndHandlesEmptyResult() {
        when(refreshTokenMapper.selectCount(any(Wrapper.class))).thenReturn(null);

        SessionPage page = service.sessions(USER_ID, 0, 10_000);

        assertThat(page.getMeta().getPage()).isEqualTo(1);
        assertThat(page.getMeta().getSize()).isEqualTo(200);
        assertThat(page.getMeta().getTotal()).isZero();
        assertThat(page.getMeta().getTotalPages()).isZero();
        assertThat(page.getItems()).isEmpty();
    }

    @Test
    void sessionsMapsStatusAndToleratesBrokenClientInfo() {
        when(refreshTokenMapper.selectCount(any(Wrapper.class))).thenReturn(3L);
        SysRefreshToken active = refreshRow();
        active.setClientInfo("{\"ip\":\"10.0.0.1\",\"userAgent\":\"JUnit\"}");
        SysRefreshToken revoked = refreshRow();
        revoked.setRevokedAt(NOW.minusSeconds(5));
        revoked.setClientInfo("");
        SysRefreshToken expired = refreshRow();
        expired.setExpiresAt(NOW.minusSeconds(5));
        expired.setClientInfo("{not-json");
        when(refreshTokenMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(active, revoked, expired));

        SessionPage page = service.sessions(USER_ID, 1, 10);

        assertThat(page.getMeta().getTotal()).isEqualTo(3);
        assertThat(page.getMeta().getTotalPages()).isEqualTo(1);
        assertThat(page.getItems()).extracting(Session::getStatus)
                .containsExactly(Session.StatusEnum.ACTIVE, Session.StatusEnum.REVOKED,
                        Session.StatusEnum.EXPIRED);
        assertThat(page.getItems().get(0).getClientIp()).isEqualTo("10.0.0.1");
        assertThat(page.getItems().get(1).getClientIp()).isEmpty();
        assertThat(page.getItems().get(2).getUserAgent()).isEmpty();
    }

    private static AuthPrincipal principal() {
        return new AuthPrincipal(USER_ID, "synth_admin", "jti", NOW.plusSeconds(900), null);
    }

    private static RefreshRequest refreshRequest(String token) {
        RefreshRequest request = new RefreshRequest();
        request.setRefreshToken(token);
        return request;
    }

    private static ChangePasswordRequest changeRequest(String oldPassword, String newPassword) {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword(oldPassword);
        request.setNewPassword(newPassword);
        return request;
    }

    private static String newPassword() {
        return "Synth-Passw0rd!2026";
    }

    private static SysRefreshToken refreshRow() {
        SysRefreshToken token = new SysRefreshToken();
        token.setId(UUID.fromString("20000000-0000-0000-0000-000000000001"));
        token.setUserId(USER_ID);
        token.setIssuedAt(NOW.minusSeconds(60));
        token.setExpiresAt(NOW.plusSeconds(3600));
        return token;
    }
}
