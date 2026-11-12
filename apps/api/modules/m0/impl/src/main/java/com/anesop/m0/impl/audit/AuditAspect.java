package com.anesop.m0.impl.audit;

import com.anesop.contracts.auth.model.LoginRequest;
import com.anesop.contracts.auth.model.PasswordResetResponse;
import com.anesop.contracts.auth.model.TokenResponse;
import com.anesop.contracts.auth.model.UserSummary;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.audit.Audited;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.stereotype.Component;

/**
 * {@link Audited} 切面：认证/授权方法的成功与失败都写审计（越权由 Web 层单独留痕）。
 *
 * <p>detail 按白名单构造：只记录操作名、尝试登录的账号（非口令）、异常类名与固定短文案；
 * 绝不记录异常 message（可能含 SQL 实参）、口令、令牌明文、请求体原文或患者标识。
 *
 * <p>失败路径：审计写失败不得掩盖原始认证/业务异常（否则错误码会变成 500）。
 * 成功路径（S-4）：审计写失败改为 fail-open——记 ERROR 日志并累加
 * {@code auth.audit.failures} 指标，响应仍按成功返回，避免审计故障放大为登录不可用。
 */
@Aspect
@Component
public class AuditAspect {

    private static final Logger LOG = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditWriter auditWriter;
    private final Counter auditFailures;

    public AuditAspect(AuditWriter auditWriter, MeterRegistry meterRegistry) {
        this.auditWriter = auditWriter;
        this.auditFailures = Counter.builder("auth.audit.failures")
                .description("认证/授权审计落库失败次数（fail-open 路径）")
                .register(meterRegistry);
    }

    @Around("@annotation(audited)")
    public Object audit(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable failure) {
            try {
                write(joinPoint, audited, null, failure);
            } catch (RuntimeException auditFailure) {
                recordFailure(audited, auditFailure);
            }
            throw failure;
        }
        // 成功路径：审计故障不得让已签发的令牌/已完成的操作用 500 返回（S-4 fail-open）。
        try {
            write(joinPoint, audited, result, null);
        } catch (RuntimeException auditFailure) {
            recordFailure(audited, auditFailure);
        }
        return result;
    }

    private void recordFailure(Audited audited, RuntimeException auditFailure) {
        auditFailures.increment();
        LOG.error("审计落库失败（fail-open，指标 auth.audit.failures）：action={}", audited.action(), auditFailure);
    }

    private void write(ProceedingJoinPoint joinPoint, Audited audited, Object result, Throwable failure) {
        AuthRequestContext context = findArgument(joinPoint.getArgs(), AuthRequestContext.class);
        auditWriter.record(
                audited.action(),
                audited.resourceType(),
                resourceId(joinPoint.getArgs(), result),
                failure == null,
                detail(joinPoint, failure),
                actorId(joinPoint.getArgs(), result),
                actorName(joinPoint.getArgs(), result, failure),
                context);
    }

    private Map<String, Object> detail(ProceedingJoinPoint joinPoint, Throwable failure) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", joinPoint.getSignature().getDeclaringType().getSimpleName()
                + "." + joinPoint.getSignature().getName());
        String username = attemptedUsername(joinPoint.getArgs());
        if (username != null) {
            detail.put("username", username);
        }
        if (failure != null) {
            // 只落异常类名 + 白名单短文案；异常 message 可能含 SQL 实参/内部路径，禁止入审计。
            detail.put("error", failure.getClass().getSimpleName());
            detail.put("reason", safeReason(failure));
        }
        return detail;
    }

    /**
     * 异常 → 固定安全文案（白名单）。未知异常统一“操作失败”，绝不回显 message。
     */
    static String safeReason(Throwable failure) {
        if (failure instanceof BadCredentialsException) {
            return "凭据无效";
        }
        if (failure instanceof DisabledException || failure instanceof LockedException) {
            return "账号状态不允许登录";
        }
        if (failure instanceof AccessDeniedException) {
            return "权限不足";
        }
        if (failure instanceof IllegalArgumentException) {
            return "参数不合法";
        }
        String type = failure.getClass().getSimpleName();
        if (type.contains("DataAccess") || type.contains("SQL") || type.contains("Persistence")) {
            return "数据访问失败";
        }
        return "操作失败";
    }

    private static String attemptedUsername(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof LoginRequest login && login.getUsername() != null) {
                return login.getUsername();
            }
            if (arg instanceof AuthPrincipal principal) {
                return principal.username();
            }
        }
        return null;
    }

    private static UUID actorId(Object[] args, Object result) {
        for (Object arg : args) {
            if (arg instanceof AuthPrincipal principal) {
                return principal.userId();
            }
        }
        if (result instanceof TokenResponse response && response.getUser() != null) {
            return parseUuid(response.getUser().getUserId());
        }
        return null;
    }

    private static String actorName(Object[] args, Object result, Throwable failure) {
        for (Object arg : args) {
            if (arg instanceof AuthPrincipal principal && principal.username() != null) {
                return principal.username();
            }
        }
        if (result instanceof TokenResponse response && response.getUser() != null) {
            return response.getUser().getUsername();
        }
        if (failure != null) {
            return attemptedUsername(args);
        }
        return null;
    }

    /**
     * 审计资源标识（S-5）：UUID 优先、session 兜底。
     *
     * <p>顺序：① 方法参数中的目标对象 UUID（USER_UPDATE / PASSWORD_RESET 的 {@code userId}）；
     * ② 方法返回值中的目标对象 UUID（USER_CREATE 的新用户 id，避免落成操作人会话）；
     * ③ 会话 ID（登出/刷新等会话动作）。只取标识，不取手机号、姓名等业务字段。
     */
    private static String resourceId(Object[] args, Object result) {
        for (Object arg : args) {
            if (arg instanceof UUID targetId) {
                return targetId.toString();
            }
        }
        String createdUserId = resultUserId(result);
        if (createdUserId != null) {
            return createdUserId;
        }
        for (Object arg : args) {
            if (arg instanceof AuthPrincipal principal && principal.sessionId() != null) {
                return principal.sessionId();
            }
        }
        return null;
    }

    private static String resultUserId(Object result) {
        if (result instanceof UserSummary summary) {
            return summary.getUserId();
        }
        if (result instanceof PasswordResetResponse response) {
            return response.getUserId();
        }
        return null;
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static <T> T findArgument(Object[] args, Class<T> type) {
        for (Object arg : args) {
            if (type.isInstance(arg)) {
                return type.cast(arg);
            }
        }
        return null;
    }
}
