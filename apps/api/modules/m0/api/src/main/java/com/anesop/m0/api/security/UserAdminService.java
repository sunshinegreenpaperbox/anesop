package com.anesop.m0.api.security;

import com.anesop.contracts.auth.model.PasswordResetResponse;
import com.anesop.contracts.auth.model.UserCreateRequest;
import com.anesop.contracts.auth.model.UserSummary;
import com.anesop.contracts.auth.model.UserSummaryPage;
import com.anesop.contracts.auth.model.UserUpdateRequest;
import java.util.UUID;

/**
 * M0 用户管理服务（契约 auth.yaml 0.5.0，M2-M0-02）。
 *
 * <p>实现位于 m0-impl；Web 适配层（api-bootstrap）只依赖本接口与契约 DTO。
 *
 * <p>安全约定：
 * <ul>
 *   <li>所有返回体只含 {@code phoneMasked}（如 {@code 138****5678}），
 *       永不返回手机号明文、{@code phone_enc} 密文、{@code phone_hash} 哈希或口令哈希；</li>
 *   <li>手机号写入时在同一事务内写密文（AES-256-GCM）、哈希（HMAC-SHA-256）、
 *       密钥版本（{@code phone_key_id}）与更新时间（{@code phone_updated_at}）；</li>
 *   <li>创建与重置口令强制 {@code must_change_password=true}；口令只以 BCrypt 存储；</li>
 *   <li>关键操作由 {@code @Audited} 切面留痕（USER_CREATE / USER_UPDATE / PASSWORD_RESET），
 *       审计 detail 不含手机号明文、口令或密文。</li>
 * </ul>
 */
public interface UserAdminService {

    /**
     * 用户分页列表。
     *
     * @param keyword 检索关键字：登录账号/显示名模糊匹配；11 位手机号按 HMAC 哈希精确匹配
     * @param page    页码（从 1 开始，服务端钳制）
     * @param size    每页条数（1–200，服务端钳制）
     */
    UserSummaryPage list(String keyword, int page, int size);

    /**
     * 用户详情。
     *
     * @throws java.util.NoSuchElementException 账号不存在或已软删除（Web 层映射 404）
     */
    UserSummary get(UUID userId);

    /**
     * 创建用户（强制首次登录改密）；登录账号大小写不敏感唯一。
     *
     * @throws IllegalArgumentException 入参不合法（手机号格式、口令强度、角色编码不存在等，Web 层 400）
     * @throws org.springframework.dao.DuplicateKeyException 登录账号/工号已存在（Web 层 409）
     */
    UserSummary create(AuthPrincipal principal, UserCreateRequest request, AuthRequestContext context);

    /**
     * 更新用户（显示名、工号、手机号、状态、角色）；字段缺省不变更。
     *
     * @throws java.util.NoSuchElementException 账号不存在或已软删除（Web 层 404）
     * @throws IllegalArgumentException 入参不合法或试图停用当前登录账号（Web 层 400）
     */
    UserSummary update(AuthPrincipal principal, UUID userId, UserUpdateRequest request,
            AuthRequestContext context);

    /**
     * 管理员重置口令：生成一次性初始口令（仅本次响应返回），强制首次登录改密，旧口令立即失效。
     *
     * @throws java.util.NoSuchElementException 账号不存在或已软删除（Web 层 404）
     */
    PasswordResetResponse resetPassword(AuthPrincipal principal, UUID userId, AuthRequestContext context);
}
