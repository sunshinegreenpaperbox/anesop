package com.anesop.api.error;

import com.anesop.api.security.AuthPrincipals;
import com.anesop.api.web.RequestContexts;
import com.anesop.contracts.auth.model.Error;
import com.anesop.contracts.auth.model.ErrorDetail;
import com.anesop.contracts.scheduling.model.Conflict;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.UserConflictException;
import com.anesop.m0.api.security.UserValidationException;
import com.anesop.m1.api.application.PatientNotInIndexException;
import com.anesop.m1.api.application.SurgeryApplicationConflictException;
import com.anesop.m1.api.application.SurgeryApplicationValidationException;
import com.anesop.m1.api.schedule.ScheduleUnprocessableException;
import com.anesop.m1.api.schedule.SchedulingConflictException;
import com.anesop.m1.api.schedule.SchedulingValidationException;
import com.anesop.m2.api.acquisition.GatewayAuthException;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2LockPreconditionException;
import com.anesop.m2.api.error.M2UnprocessableException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.api.error.PacuDischargePreconditionException;
import com.anesop.m2.api.preop.PreopConflictException;
import com.anesop.m2.api.preop.PreopUnprocessableException;
import com.anesop.m2.api.preop.PreopValidationException;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.platform.M0ConflictException;
import com.anesop.m0.api.platform.M0UnprocessableException;
import com.anesop.m0.api.platform.M0ValidationException;
import com.anesop.m4.api.error.M4ConflictException;
import com.anesop.m4.api.error.M4UnprocessableException;
import com.anesop.m4.api.error.M4ValidationException;
import com.anesop.m5.api.error.M5ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    /**
     * 网关令牌校验失败（M3-M8-01）：统一 401，文案固定；不回显令牌、期望值或配置状态，
     * 也不写业务审计（避免把未认证请求头带入审计）。
     */
    @ExceptionHandler(GatewayAuthException.class)
    public ResponseEntity<Error> handleGatewayAuth(GatewayAuthException exception, HttpServletRequest request) {
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
     * 数据库唯一约束兜底（并发创建，M2-M0-02 / M2-M1-01）：同样映射为 409。
     * 返回固定文案，不回显数据库约束名、SQL 实参或冲突值。
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Error> handleDuplicateKey(DuplicateKeyException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "STATE_CONFLICT", "数据已存在或已被修改", request);
    }

    /**
     * 平台能力字段级校验（M8-M0-04：字典/文件字段）：400 {@code PARAM_INVALID}，
     * field 由异常携带，文案由服务端固定提供；不回显 Base64 原文、文件内容片段或患者标识。
     */
    @ExceptionHandler(M0ValidationException.class)
    public ResponseEntity<Error> handlePlatformValidation(M0ValidationException exception,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "PARAM_INVALID", "请求参数校验失败", request,
                List.of(ErrorResponses.detail(exception.field(), exception.getMessage())));
    }

    /**
     * 平台能力业务规则拒绝（M8-M0-04：文件类型/大小）：422 {@code BUSINESS_RULE_VIOLATION}，
     * field 固定为 contentType/sizeBytes，文案不含文件内容。
     */
    @ExceptionHandler(M0UnprocessableException.class)
    public ResponseEntity<Error> handlePlatformUnprocessable(M0UnprocessableException exception,
            HttpServletRequest request) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION",
                "业务规则拒绝", request,
                List.of(ErrorResponses.detail(exception.field(), exception.getMessage())));
    }

    /**
     * 平台能力状态冲突（M8-M0-04：字典项编码重复/并发更新）：409 {@code STATE_CONFLICT}，
     * 不回显冲突编码值。
     */
    @ExceptionHandler(M0ConflictException.class)
    public ResponseEntity<Error> handlePlatformConflict(M0ConflictException exception,
            HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "STATE_CONFLICT", "数据已存在或已被修改", request);
    }

    /**
     * 手术申请字段级校验（M2-M1-01）：400 {@code PARAM_INVALID}，field 由服务端固定提供，
     * 不回显用户输入原文或患者标识。
     */
    @ExceptionHandler(SurgeryApplicationValidationException.class)
    public ResponseEntity<Error> handleSurgeryApplicationValidation(
            SurgeryApplicationValidationException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "PARAM_INVALID", "请求参数校验失败", request,
                List.of(ErrorResponses.detail(exception.field(), exception.getMessage())));
    }

    /**
     * 手术申请业务冲突（重复申请号/非法状态流转/乐观锁版本冲突，M2-M1-01）：409。
     */
    @ExceptionHandler(SurgeryApplicationConflictException.class)
    public ResponseEntity<Error> handleSurgeryApplicationConflict(
            SurgeryApplicationConflictException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "STATE_CONFLICT", "申请状态或版本冲突，操作未生效", request);
    }

    /**
     * 患者不在患者快照内（M2-M1-01）：创建/导入接口无资源路径参数，按契约语义返回 422。
     */
    @ExceptionHandler(PatientNotInIndexException.class)
    public ResponseEntity<Error> handlePatientNotInIndex(
            PatientNotInIndexException exception, HttpServletRequest request) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION",
                "患者不在本系统患者快照内", request,
                List.of(ErrorResponses.detail("patientId", "患者主索引未同步或标识不存在")));
    }

    /**
     * 排程字段级校验（M2-M2-01）：400 {@code PARAM_INVALID}，field 由服务端固定提供。
     */
    @ExceptionHandler(SchedulingValidationException.class)
    public ResponseEntity<Error> handleSchedulingValidation(SchedulingValidationException exception,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "PARAM_INVALID", "请求参数校验失败", request,
                List.of(ErrorResponses.detail(exception.field(), exception.getMessage())));
    }

    /**
     * 排程语义拒绝（申请不存在/状态不允许，M2-M2-01）：422 {@code BUSINESS_RULE_VIOLATION}。
     */
    @ExceptionHandler(ScheduleUnprocessableException.class)
    public ResponseEntity<Error> handleScheduleUnprocessable(ScheduleUnprocessableException exception,
            HttpServletRequest request) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION",
                "排程业务规则拒绝", request,
                List.of(ErrorResponses.detail(exception.field(), exception.getMessage())));
    }

    /**
     * 排程冲突（M2-M2-01）：409 {@code SCHEDULE_CONFLICT}，同时在 {@code conflicts[]} 返回
     * 结构化冲突（类型/说明/冲突台次），{@code details} 同步便于前端直接展示。
     */
    @ExceptionHandler(SchedulingConflictException.class)
    public ResponseEntity<Object> handleSchedulingConflict(SchedulingConflictException exception,
            HttpServletRequest request) {
        List<ErrorDetail> details = new ArrayList<>();
        for (Conflict conflict : exception.conflicts()) {
            details.add(ErrorResponses.detail(conflict.getConflictType(), conflict.getMessage()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "SCHEDULE_CONFLICT");
        body.put("message", "排程冲突，操作未生效");
        body.put("traceId", RequestContexts.traceId(request));
        body.put("details", details);
        body.put("conflicts", exception.conflicts());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * 术前访视字段级校验（M2-M4-01）：400 {@code PARAM_INVALID}，field 由服务端固定提供，
     * 不回显病史原文、患者标识或评分输入原文。
     */
    @ExceptionHandler(PreopValidationException.class)
    public ResponseEntity<Error> handlePreopValidation(PreopValidationException exception,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "PARAM_INVALID", "请求参数校验失败", request,
                List.of(ErrorResponses.detail(exception.field(), exception.getMessage())));
    }

    /**
     * 术前访视状态/版本冲突（M2-M4-01）：409 {@code STATE_CONFLICT}。
     * 已最终化只能新建版本、乐观锁过期、同申请重复草稿、申请状态不允许。
     */
    @ExceptionHandler(PreopConflictException.class)
    public ResponseEntity<Error> handlePreopConflict(PreopConflictException exception,
            HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "STATE_CONFLICT", "访视单状态或版本冲突，操作未生效",
                request);
    }

    /**
     * 术前访视语义拒绝（M2-M4-01）：422 {@code BUSINESS_RULE_VIOLATION}，如申请不存在/不可见。
     */
    @ExceptionHandler(PreopUnprocessableException.class)
    public ResponseEntity<Error> handlePreopUnprocessable(PreopUnprocessableException exception,
            HttpServletRequest request) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION",
                "术前访视业务规则拒绝", request,
                List.of(ErrorResponses.detail(exception.field(), exception.getMessage())));
    }

    /**
     * 知情同意/三方核查字段级校验（M2-M5-01）：400 {@code PARAM_INVALID}，field 由服务端固定提供，
     * 不回显签署人姓名、原因原文、问题项原文或患者标识。
     */
    @ExceptionHandler(M2ValidationException.class)
    public ResponseEntity<Error> handleM2Validation(M2ValidationException exception,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "PARAM_INVALID", "请求参数校验失败", request,
                List.of(ErrorResponses.detail(exception.field(), exception.getMessage())));
    }

    /**
     * M2 状态/版本冲突（知情同意/三方核查/麻醉记录锁单与修订）：409 {@code STATE_CONFLICT}。
     * 重复签署、已撤销后再次签署、版本过期、重复锁单、锁单后写入、未锁单修订等统一文案。
     */
    @ExceptionHandler(M2ConflictException.class)
    public ResponseEntity<Error> handleM2Conflict(M2ConflictException exception,
            HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "STATE_CONFLICT", "状态或版本冲突，操作未生效", request);
    }

    /**
     * 锁单前置条件未满足（M3-M2-03）：409 {@code STATE_CONFLICT}，{@code details[]} 逐项列出
     * 未满足条件（field=条件码，reason=中文说明），供前端展示前置清单；不回显患者标识或
     * 数据库约束名。
     */
    @ExceptionHandler(M2LockPreconditionException.class)
    public ResponseEntity<Error> handleM2LockPrecondition(
            M2LockPreconditionException exception, HttpServletRequest request) {
        List<ErrorDetail> details = new ArrayList<>();
        for (M2LockPreconditionException.UnmetCondition unmet : exception.unmetConditions()) {
            details.add(ErrorResponses.detail(unmet.code(), unmet.message()));
        }
        return response(HttpStatus.CONFLICT, "STATE_CONFLICT",
                "锁单前置条件未满足，操作未生效", request, details);
    }

    /**
     * PACU 转出条件未满足（M3-M10-01）：409 {@code STATE_CONFLICT}，{@code details[]} 逐项列出
     * 未满足条件（field=条件码，reason=中文说明），供前端展示转出条件清单；不回显 SBAR 交接原文、
     * Aldrete 备注原文或患者标识。
     */
    @ExceptionHandler(PacuDischargePreconditionException.class)
    public ResponseEntity<Error> handlePacuDischargePrecondition(
            PacuDischargePreconditionException exception, HttpServletRequest request) {
        List<ErrorDetail> details = new ArrayList<>();
        for (PacuDischargePreconditionException.UnmetCondition unmet
                : exception.unmetConditions()) {
            details.add(ErrorResponses.detail(unmet.code(), unmet.message()));
        }
        return response(HttpStatus.CONFLICT, "STATE_CONFLICT",
                "复苏转出条件未满足，操作未生效", request, details);
    }

    /**
     * 知情同意/三方核查语义拒绝（M2-M5-01）：422 {@code BUSINESS_RULE_VIOLATION}，
     * 如创建同意时申请不可见或模板版本不存在。请求体参数错误不回显资源存在性。
     */
    @ExceptionHandler(M2UnprocessableException.class)
    public ResponseEntity<Error> handleM2Unprocessable(M2UnprocessableException exception,
            HttpServletRequest request) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION",
                "知情同意/三方核查业务规则拒绝", request,
                List.of(ErrorResponses.detail(exception.field(), exception.getMessage())));
    }

    /**
     * 药品目录/麻精五专字段级校验（M4-M15-01）：400 {@code PARAM_INVALID}，field 由服务端
     * 固定提供，不回显处方号原文、人员姓名或患者标识。
     */
    @ExceptionHandler(M4ValidationException.class)
    public ResponseEntity<Error> handleM4Validation(M4ValidationException exception,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "PARAM_INVALID", "请求参数校验失败", request,
                List.of(ErrorResponses.detail(exception.field(), exception.getMessage())));
    }

    /**
     * M4 业务规则拒绝（M4-M15-01 药品/五专、M4-M14-01 耗材/UDI）：422
     * {@code BUSINESS_RULE_VIOLATION}，如药品未收录于字典、耗材已过效期、高值耗材裸数量
     * 出入库、UDI 绑定记录不存在/不可见。业务语义拒绝，非权限 403。
     */
    /**
     * 看板字段级校验（M5-M18-01）：400 {@code PARAM_INVALID}，field 由服务端固定提供，
     * 不回显原始入参（日期原串）。
     */
    @ExceptionHandler(M5ValidationException.class)
    public ResponseEntity<Error> handleM5Validation(M5ValidationException exception,
            HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "PARAM_INVALID", "请求参数校验失败", request,
                List.of(ErrorResponses.detail(exception.field(), exception.getMessage())));
    }

    @ExceptionHandler(M4UnprocessableException.class)
    public ResponseEntity<Error> handleM4Unprocessable(M4UnprocessableException exception,
            HttpServletRequest request) {
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION",
                "业务规则拒绝，操作未生效", request,
                List.of(ErrorResponses.detail(exception.field(), exception.getMessage())));
    }

    /**
     * M4 状态冲突（药品/五专/M4-M14-01 耗材/UDI）：409 {@code STATE_CONFLICT}。日结不平时
     * {@code details[]} 给出期望值与实盘值（field=数量字段），供前端展示平账差异；
     * 文案不含数据库约束名与 SQL 实参。
     */
    @ExceptionHandler(M4ConflictException.class)
    public ResponseEntity<Error> handleM4Conflict(M4ConflictException exception,
            HttpServletRequest request) {
        List<ErrorDetail> details = new ArrayList<>();
        for (M4ConflictException.Detail detail : exception.details()) {
            details.add(ErrorResponses.detail(detail.field(), detail.reason()));
        }
        return response(HttpStatus.CONFLICT, "STATE_CONFLICT", "状态或版本冲突，操作未生效",
                request, details);
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
