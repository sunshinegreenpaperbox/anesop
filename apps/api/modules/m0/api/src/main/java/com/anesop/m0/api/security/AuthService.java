package com.anesop.m0.api.security;

import com.anesop.contracts.auth.model.ChangePasswordRequest;
import com.anesop.contracts.auth.model.CurrentUser;
import com.anesop.contracts.auth.model.LoginRequest;
import com.anesop.contracts.auth.model.RefreshRequest;
import com.anesop.contracts.auth.model.SessionPage;
import com.anesop.contracts.auth.model.TokenResponse;
import java.util.UUID;

/**
 * M0 认证与令牌服务（契约 auth.yaml 0.3.0 的应用服务边界）。
 *
 * <p>实现位于 m0-impl；Web 适配层（api-bootstrap）只依赖本接口与契约 DTO。
 * 所有失败路径抛 Spring Security 标准异常（BadCredentials/Disabled/Locked），
 * 由 Web 层统一映射为契约错误码；异常信息不得包含口令、令牌或账号是否存在。
 */
public interface AuthService {

    /**
     * 本地账号登录；成功后签发访问令牌并落库刷新令牌。
     *
     * @throws org.springframework.security.authentication.BadCredentialsException 账号或口令错误
     * @throws org.springframework.security.authentication.DisabledException 账号停用或认证方式未开通
     * @throws org.springframework.security.authentication.LockedException 账号锁定
     */
    TokenResponse login(LoginRequest request, AuthRequestContext context);

    /**
     * 刷新访问令牌：轮换刷新令牌，旧令牌立即失效。
     *
     * @throws org.springframework.security.authentication.BadCredentialsException 刷新令牌不存在/已撤销/已过期
     */
    TokenResponse refresh(RefreshRequest request, AuthRequestContext context);

    /** 登出：撤销当前会话的刷新令牌与访问令牌（幂等）。 */
    void logout(AuthPrincipal principal, AuthRequestContext context);

    /**
     * 修改当前用户口令：校验旧口令与新口令强度，成功后清除强制改密标记。
     *
     * @throws org.springframework.security.authentication.BadCredentialsException 旧口令错误
     * @throws org.springframework.security.authentication.BadCredentialsException 用户不存在或已停用
     * @throws IllegalArgumentException 新口令不满足强度规则（由 Web 层映射为 400）
     */
    void changePassword(AuthPrincipal principal, ChangePasswordRequest request, AuthRequestContext context);

    /** 当前用户的角色、权限点与数据范围快照（每次实时查询，权限变更即时生效）。 */
    CurrentUser currentUser(UUID userId);

    /** 当前用户的登录会话分页（按签发时间倒序）。 */
    SessionPage sessions(UUID userId, int page, int size);
}
