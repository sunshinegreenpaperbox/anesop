package com.anesop.api.error;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.api.web.RequestContexts;
import com.anesop.contracts.auth.model.Error;
import com.anesop.contracts.auth.model.ErrorDetail;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.UserConflictException;
import com.anesop.m0.api.security.UserValidationException;
import com.anesop.m0.api.audit.AuditWriter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * REST 异常 → 契约错误响应映射（auth.yaml 的 400/401/403/422）。
 *
 * <p>安全语义：口令错误与账号不存在统一 401（不暴露账号是否存在）；停用/锁定/认证方式
 * 未开通统一 422；越权 403 同时写审计（方法级 {@code @PreAuthorize} 拒绝走这里，
 * URL 级拒绝走 {@link com.anesop.api.security.RestAccessDeniedHandler}）。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final AuditWriter auditWriter;

    public ApiExceptionHandler(AuditWriter auditWriter) {
        this.auditWriter = auditWriter;
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Error> handleBadCredentials(BadCredentialsException exception, HttpServletRequest request) {
        return response(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHORIZED", "账号或口令错误，或令牌已失效", request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Error> handleAuthentication(AuthenticationException exception, HttpServletRequest request) {
        return response(HttpStatus.UNAUTHORIZED, "AUTH_UNAUTHORIZED", "未认证或令牌已失效", request);
    }

    @ExceptionHandler({DisabledException.class, LockedException.class})
    public ResponseEntity<Error> handleAccountState(AuthenticationException exception, HttpServletRequest request) {
        // S-2：停用/锁定/非本地认证统一模糊文案，不通过文案暴露账号状态。
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION",
                "账号状态或认证方式不允许登录", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Error> handleIllegalArgument(IllegalArgumentException exception,
            HttpServletRequest request) {
        // 口令强度等业务校验：文案由 PasswordPolicy 固定提供，不含用户输入。
        return response(HttpStatus.BAD_REQUEST, "PARAM_INVALID", "请求参数校验失败", request,
                List.of(ErrorResponses.detail("newPassword", exception.getMessage())));
    }

    /**
     * 用户管理字段级校验（M2-M0-02）：字段名由异常携带，避免把所有 400 都标成 newPassword。
     * 文案由服务端固定提供，不含手机号明文、口令或密文。
     */
    @ExceptionHandler(UserValidationException.class)
    public ResponseEntity<Error> handleUserValidation(UserValidationException exception,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "PARAM_INVALID", "请求参数校验失败", request,
                List.of(ErrorResponses.detail(exception.field(), exception.getMessage())));
    }

    /**
     * 用户不存在或已软删除（M2-M0-02）：统一 404 {@code RESOURCE_NOT_FOUND}，不回显查询细节。
     */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Error> handleNotFound(NoSuchElementException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "资源不存在或不可见", request);
    }

    /**
     * 用户管理唯一性冲突（M2-M0-02）：登录账号（大小写不敏感）或工号重复 → 409。
     * 返回固定文案，不回显冲突值。
     */
    @ExceptionHandler(UserConflictException.class)
    public ResponseEntity<Error> handleUserConflict(UserConflictException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "STATE_CONFLICT", "登录账号或工号已存在，或数据已被修改", request);
    }

    /**
     * 数据库唯一约束兜底（并发创建，M2-M0-02）：同样映射为 409。
     * 返回固定文案，不回显数据库约束名、SQL 实参或冲突值。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Error> handleDuplicateKey(DuplicateKeyException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "STATE_CONFLICT", "登录账号或工号已存在，或数据已被修改", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Error> handleValidation(MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        List<ErrorDetail> details = new ArrayList<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            details.add(ErrorResponses.detail(fieldError.getField(), fieldError.getDefaultMessage()));
        }
        return response(HttpStatus.BAD_REQUEST, "PARAM_INVALID", "请求参数校验失败", request, details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Error> handleUnreadable(HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "PARAM_INVALID", "请求体格式非法", request);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Error> handleBadParameter(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "PARAM_INVALID", "请求参数校验失败", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Error> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        AuthRequestContext context = RequestContexts.from(request);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        java.util.UUID actorId = null;
        String actorName = null;
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            actorId = AuthPrincipals.from(jwt).userId();
            actorName = jwt.getClaimAsString("username");
        }
        try {
            auditWriter.record("ACCESS_DENIED", "API", request.getRequestURI(), false,
                    Map.of("path", request.getRequestURI(), "method", request.getMethod()),
                    actorId, actorName, context);
        } catch (RuntimeException auditFailure) {
            LOG.error("403 越权审计落库失败：path={}", request.getRequestURI(), auditFailure);
        }
        return response(HttpStatus.FORBIDDEN, "AUTH_FORBIDDEN", "无权访问该资源或数据范围不足", request);
    }

    private ResponseEntity<Error> response(HttpStatus status, String code, String message,
            HttpServletRequest request) {
        return response(status, code, message, request, List.of());
    }

    private ResponseEntity<Error> response(HttpStatus status, String code, String message,
            HttpServletRequest request, List<ErrorDetail> details) {
        Error error = ErrorResponses.of(code, message, RequestContexts.traceId(request), details);
        return ResponseEntity.status(status).body(error);
    }
}
