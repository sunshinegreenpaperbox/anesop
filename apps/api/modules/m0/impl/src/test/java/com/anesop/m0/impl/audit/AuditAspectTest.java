package com.anesop.m0.impl.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.auth.model.CurrentUser;
import com.anesop.contracts.auth.model.LoginRequest;
import com.anesop.contracts.auth.model.PasswordResetResponse;
import com.anesop.contracts.auth.model.TokenResponse;
import com.anesop.contracts.auth.model.UserSummary;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.audit.Audited;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;

/**
 * 审计切面单测（M2-M0-03）：成功/失败留痕、detail 白名单、资源与操作人解析、
 * fail-open（成功路径审计故障不改变响应）与 fail-safe（失败路径保留原始异常）。
 */
class AuditAspectTest {

    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("10.0.0.1", "JUnit", "trace-1");

    private AuditWriter writer;
    private SimpleMeterRegistry registry;
    private AuditAspect aspect;
    private ProceedingJoinPoint joinPoint;
    private Signature signature;
    private Audited audited;

    @BeforeEach
    void setUp() throws Exception {
        writer = mock(AuditWriter.class);
        registry = new SimpleMeterRegistry();
        aspect = new AuditAspect(writer, registry);

        joinPoint = mock(ProceedingJoinPoint.class);
        signature = mock(Signature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getDeclaringType()).thenReturn((Class) AuditedSample.class);
        when(signature.getName()).thenReturn("run");
        audited = AuditedSample.class.getMethod("run", String.class).getAnnotation(Audited.class);
    }

    @Test
    void successWritesAuditWithTokenResponseActorAndContext() throws Throwable {
        TokenResponse response = new TokenResponse();
        CurrentUser user = new CurrentUser();
        user.setUserId(USER_ID.toString());
        user.setUsername("synth_admin");
        response.setUser(user);
        when(joinPoint.getArgs()).thenReturn(new Object[] {new Object(), CONTEXT});
        when(joinPoint.proceed()).thenReturn(response);

        Object result = aspect.audit(joinPoint, audited);

        assertThat(result).isSameAs(response);
        verify(writer).record(eq("LOGIN"), eq("USER"), isNull(), eq(true),
                anyMap(), eq(USER_ID), eq("synth_admin"), eq(CONTEXT));
    }

    @Test
    void failureWritesAuditThenRethrowsOriginal() throws Throwable {
        BadCredentialsException failure = new BadCredentialsException("账号或口令错误");
        when(joinPoint.getArgs()).thenReturn(new Object[] {"synth_admin"});
        when(joinPoint.proceed()).thenThrow(failure);

        assertThatThrownBy(() -> aspect.audit(joinPoint, audited)).isSameAs(failure);

        verify(writer).record(eq("LOGIN"), eq("USER"), isNull(), eq(false), anyMap(),
                isNull(), isNull(), isNull());
    }

    @Test
    void principalArgumentsProvideActorAndSessionResource() throws Throwable {
        AuthPrincipal principal = new AuthPrincipal(USER_ID, "synth_admin", "jti",
                Instant.parse("2026-11-13T01:00:00Z"), "session-1");
        when(joinPoint.getArgs()).thenReturn(new Object[] {principal, CONTEXT});
        when(joinPoint.proceed()).thenReturn(null);

        aspect.audit(joinPoint, audited);

        verify(writer).record(eq("LOGIN"), eq("USER"), eq("session-1"), eq(true), anyMap(),
                eq(USER_ID), eq("synth_admin"), eq(CONTEXT));
    }

    @Test
    void uuidArgumentTakesPrecedenceAsResourceId() throws Throwable {
        UUID target = UUID.fromString("30000000-0000-0000-0000-000000000003");
        when(joinPoint.getArgs()).thenReturn(new Object[] {target, CONTEXT});
        when(joinPoint.proceed()).thenReturn(null);

        aspect.audit(joinPoint, audited);

        verify(writer).record(eq("LOGIN"), eq("USER"), eq(target.toString()), eq(true), anyMap(),
                isNull(), isNull(), eq(CONTEXT));
    }

    @Test
    void resultUserSummarySuppliesResourceAndActorIdParsingFallsBack() throws Throwable {
        UserSummary summary = new UserSummary();
        summary.setUserId("not-a-uuid");
        when(joinPoint.getArgs()).thenReturn(new Object[] {"x"});
        when(joinPoint.proceed()).thenReturn(summary);

        aspect.audit(joinPoint, audited);

        verify(writer).record(eq("LOGIN"), eq("USER"), eq("not-a-uuid"), eq(true), anyMap(),
                isNull(), isNull(), isNull());
    }

    @Test
    void passwordResetResponseSuppliesResourceId() throws Throwable {
        PasswordResetResponse response = new PasswordResetResponse();
        response.setUserId(USER_ID.toString());
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.proceed()).thenReturn(response);

        aspect.audit(joinPoint, audited);

        verify(writer).record(eq("LOGIN"), eq("USER"), eq(USER_ID.toString()), eq(true), anyMap(),
                isNull(), isNull(), isNull());
    }

    @Test
    void successPathIsFailOpenWhenAuditWriteFails() throws Throwable {
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.proceed()).thenReturn("ok");
        doThrow(new IllegalStateException("db down")).when(writer)
                .record(anyString(), anyString(), any(), anyBoolean(), anyMap(), any(), any(), any());

        Object result = aspect.audit(joinPoint, audited);

        assertThat(result).isEqualTo("ok");
        assertThat(registry.counter("auth.audit.failures").count()).isEqualTo(1.0d);
    }

    @Test
    void failurePathKeepsOriginalExceptionEvenIfAuditWriteFails() throws Throwable {
        BadCredentialsException failure = new BadCredentialsException("bad");
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.proceed()).thenThrow(failure);
        doThrow(new IllegalStateException("db down")).when(writer)
                .record(anyString(), anyString(), any(), anyBoolean(), anyMap(), any(), any(), any());

        assertThatThrownBy(() -> aspect.audit(joinPoint, audited)).isSameAs(failure);
        assertThat(registry.counter("auth.audit.failures").count()).isEqualTo(1.0d);
    }

    @Test
    void detailUsesWhitelistedReasonForEachFailureType() {
        assertThat(AuditAspect.safeReason(new BadCredentialsException("x"))).isEqualTo("凭据无效");
        assertThat(AuditAspect.safeReason(new DisabledException("x"))).isEqualTo("账号状态不允许登录");
        assertThat(AuditAspect.safeReason(new LockedException("x"))).isEqualTo("账号状态不允许登录");
        assertThat(AuditAspect.safeReason(new AccessDeniedException("x"))).isEqualTo("权限不足");
        assertThat(AuditAspect.safeReason(new IllegalArgumentException("x"))).isEqualTo("参数不合法");
        assertThat(AuditAspect.safeReason(new DataAccessResourceFailureException("x")))
                .isEqualTo("数据访问失败");
        assertThat(AuditAspect.safeReason(new SQLFailure())).isEqualTo("数据访问失败");
        assertThat(AuditAspect.safeReason(new IllegalStateException("x"))).isEqualTo("操作失败");
    }

    @Test
    void detailContainsOperationAndAttemptedUsernameButNeverMessage() throws Throwable {
        BadCredentialsException failure = new BadCredentialsException("secret-message must not leak");
        LoginRequest login = new LoginRequest();
        login.setUsername("synth_admin");
        when(joinPoint.getArgs()).thenReturn(new Object[] {login});
        when(joinPoint.proceed()).thenThrow(failure);

        assertThatThrownBy(() -> aspect.audit(joinPoint, audited)).isSameAs(failure);

        org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(writer).record(anyString(), anyString(), any(), anyBoolean(), captor.capture(),
                any(), any(), any());
        Map<String, Object> detail = captor.getValue();
        assertThat(detail).containsEntry("operation", "AuditedSample.run")
                .containsEntry("error", "BadCredentialsException")
                .containsEntry("reason", "凭据无效");
        assertThat(detail).containsEntry("username", "synth_admin");
        assertThat(detail.values()).noneMatch(value -> String.valueOf(value).contains("secret-message"));
    }

    @Test
    void principalUsernameIsWrittenToDetail() throws Throwable {
        AuthPrincipal principal = new AuthPrincipal(USER_ID, "synth_admin", "jti",
                Instant.parse("2026-11-13T01:00:00Z"), null);
        when(joinPoint.getArgs()).thenReturn(new Object[] {principal});
        when(joinPoint.proceed()).thenReturn(null);

        aspect.audit(joinPoint, audited);

        org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(writer).record(anyString(), anyString(), any(), anyBoolean(), captor.capture(),
                any(), any(), any());
        assertThat(captor.getValue()).containsEntry("username", "synth_admin");
    }

    /** 携带 @Audited 的合成目标类。 */
    static class AuditedSample {
        @Audited(action = "LOGIN", resourceType = "USER")
        public void run(String ignored) {
            // 仅用于获取注解实例。
        }
    }

    /** 类名含 SQL 的异常，用于覆盖“数据访问失败”白名单分支。 */
    static class SQLFailure extends RuntimeException {
    }
}
