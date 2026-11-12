package com.anesop.m0.impl.auth;

import com.anesop.contracts.auth.model.PageMeta;
import com.anesop.contracts.auth.model.PasswordResetResponse;
import com.anesop.contracts.auth.model.UserCreateRequest;
import com.anesop.contracts.auth.model.UserStatus;
import com.anesop.contracts.auth.model.UserSummary;
import com.anesop.contracts.auth.model.UserSummaryPage;
import com.anesop.contracts.auth.model.UserUpdateRequest;
import com.anesop.m0.api.audit.Audited;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.PasswordPolicy;
import com.anesop.m0.api.security.UserAdminService;
import com.anesop.m0.api.security.UserConflictException;
import com.anesop.m0.api.security.UserValidationException;
import com.anesop.m0.impl.crypto.PhoneCrypto;
import com.anesop.m0.impl.crypto.PhoneCryptoException;
import com.anesop.m0.impl.crypto.PhoneHasher;
import com.anesop.m0.impl.persistence.entity.SysRole;
import com.anesop.m0.impl.persistence.entity.SysUser;
import com.anesop.m0.impl.persistence.mapper.SysPermissionMapper;
import com.anesop.m0.impl.persistence.mapper.SysRefreshTokenMapper;
import com.anesop.m0.impl.persistence.mapper.SysRoleMapper;
import com.anesop.m0.impl.persistence.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户管理服务实现（契约 auth.yaml 0.5.0，M2-M0-02）。
 *
 * <p>设计要点（方案书 §3.9.3 字段加密与脱敏）：
 * <ul>
 *   <li>手机号写路径：规范化 → AES-256-GCM 加密（随机 IV）→ HMAC-SHA-256 哈希 →
 *       同事务写 {@code phone_enc}/{@code phone_hash}/{@code phone_key_id}/{@code phone_updated_at}；
 *       库层 {@code ck_sys_user_phone_crypto} 约束保证四列同生同灭；</li>
 *   <li>手机号读路径：只在服务内部解密并立即脱敏为 {@code 138****5678}，
 *       密钥版本不匹配或密文损坏时降级为不展示（记 WARN，不含密文）；</li>
 *   <li>口令：创建与管理员重置都强制 {@code must_change_password=true}，只存 BCrypt 哈希；
 *       重置返回一次性初始口令（仅本次响应，不落库/日志/审计）；</li>
 *   <li>审计：{@code @Audited} 记录 USER_CREATE / USER_UPDATE / PASSWORD_RESET，
 *       detail 由切面白名单构造，不含手机号明文、密文、哈希或口令。</li>
 * </ul>
 */
@Service
public class UserAdminServiceImpl implements UserAdminService {

    private static final Logger LOG = LoggerFactory.getLogger(UserAdminServiceImpl.class);

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int GENERATED_PASSWORD_BYTES = 15;
    private static final int MAX_GENERATE_ATTEMPTS = 5;
    private static final int MAX_USERNAME_LENGTH = 64;
    private static final int MAX_DISPLAY_NAME_LENGTH = 100;
    private static final int MAX_EMPLOYEE_NO_LENGTH = 64;

    /** 中国内地手机号：11 位、1 开头、第二位 3–9（与契约描述一致）。 */
    private static final String MOBILE_PATTERN = "1[3-9]\\d{9}";

    private static final String PHONE_FORMAT_MESSAGE = "手机号格式不正确（需为中国内地 11 位号码）";

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysRefreshTokenMapper refreshTokenMapper;
    private final PhoneCrypto phoneCrypto;
    private final PhoneHasher phoneHasher;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 单例注入的 MyBatis Mapper 代理，生命周期由容器管理，服务不对外暴露该引用（评审 Q-1）")
    public UserAdminServiceImpl(SysUserMapper userMapper,
            SysRoleMapper roleMapper,
            SysPermissionMapper permissionMapper,
            SysRefreshTokenMapper refreshTokenMapper,
            PhoneCrypto phoneCrypto,
            PhoneHasher phoneHasher,
            PasswordEncoder passwordEncoder,
            Clock authClock) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.phoneCrypto = phoneCrypto;
        this.phoneHasher = phoneHasher;
        this.passwordEncoder = passwordEncoder;
        this.clock = authClock;
    }

    @Override
    public UserSummaryPage list(String keyword, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        String needle = keyword == null ? "" : keyword.trim();
        // S-11：手机号关键字先规范化（去空格/短横线/括号、去 +86/0086），
        // 使用户按“+86 138-0000-5678”等书写方式也能命中同一个 HMAC 哈希。
        String phoneNeedle = PhoneHasher.normalize(needle);

        QueryWrapper<SysUser> wrapper = new QueryWrapper<>();
        if (!needle.isEmpty()) {
            wrapper.and(condition -> {
                condition.like("username", needle).or().like("display_name", needle);
                if (isMobile(phoneNeedle)) {
                    // 手机号只按 HMAC 哈希等值匹配：明文不参与查询，也不作为 LIKE 模式。
                    condition.or().eq("phone_hash", phoneHasher.hash(phoneNeedle));
                }
            });
        }
        Long counted = userMapper.selectCount(wrapper);
        long total = counted == null ? 0 : counted;
        long offset = (long) (safePage - 1) * safeSize;

        List<UserSummary> items = new ArrayList<>();
        if (total > offset) {
            // 分页参数已钳制为 int（page>=1, 1<=size<=200），拼接 LIMIT/OFFSET 无注入风险。
            List<SysUser> rows = userMapper.selectList(wrapper
                    .orderByDesc("created_at")
                    .orderByAsc("id")
                    .last("LIMIT " + safeSize + " OFFSET " + offset));
            for (SysUser row : rows) {
                items.add(toSummary(row));
            }
        }

        PageMeta meta = new PageMeta();
        meta.setPage(safePage);
        meta.setSize(safeSize);
        meta.setTotal((int) Math.min(Integer.MAX_VALUE, total));
        meta.setTotalPages(total == 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (total + safeSize - 1) / safeSize));

        UserSummaryPage result = new UserSummaryPage();
        result.setItems(items);
        result.setMeta(meta);
        return result;
    }

    @Override
    public UserSummary get(UUID userId) {
        return toSummary(requireUser(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(action = "USER_CREATE", resourceType = "USER")
    public UserSummary create(AuthPrincipal principal, UserCreateRequest request, AuthRequestContext context) {
        String username = requireText(request.getUsername(), "username", "登录账号", MAX_USERNAME_LENGTH);
        String displayName = requireText(request.getDisplayName(), "displayName", "显示名",
                MAX_DISPLAY_NAME_LENGTH);
        if (usernameExists(username)) {
            // 大小写不敏感判重（与 uk_sys_user_username 的 lower(username) 一致），
            // 在事务内先给出干净的 409：撞库报错会让事务进入 aborted，失败审计只能 fail-open。
            throw new UserConflictException("登录账号已存在");
        }
        String initialPassword = request.getInitialPassword() == null ? "" : request.getInitialPassword();
        try {
            PasswordPolicy.validate(initialPassword, username, null);
        } catch (IllegalArgumentException e) {
            throw new UserValidationException("initialPassword", e.getMessage());
        }
        Instant now = Instant.now(clock);
        SysUser user = new SysUser();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(initialPassword));
        user.setDisplayName(displayName);
        user.setEmployeeNo(optionalText(request.getEmployeeNo(), "employeeNo", MAX_EMPLOYEE_NO_LENGTH));
        user.setStatus(1);
        // 创建后必须首次登录改密：管理员知道初始口令，不能长期有效。
        user.setMustChangePassword(true);
        user.setFailedAttempts(0);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        user.setCreatedBy(principal == null ? null : principal.userId());
        user.setUpdatedBy(principal == null ? null : principal.userId());
        user.setVersion(0L);
        user.setDeleted(false);
        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            PhoneWrite phone = phoneWrite(request.getPhone(), now);
            user.setPhoneEnc(phone.encrypted());
            user.setPhoneHash(phone.hash());
            user.setPhoneKeyId(phone.keyId());
            user.setPhoneUpdatedAt(phone.updatedAt());
        }
        userMapper.insert(user);

        applyRoles(user.getId(), request.getRoles(), principal);
        return toSummary(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(action = "USER_UPDATE", resourceType = "USER")
    public UserSummary update(AuthPrincipal principal, UUID userId, UserUpdateRequest request,
            AuthRequestContext context) {
        SysUser existing = requireUser(userId);
        if (request.getRoles() != null && principal != null && userId.equals(principal.userId())) {
            // S-10：禁止修改自己的角色，避免管理员自助提权或自削权限。
            throw new AccessDeniedException("不能修改当前登录账号自身的角色");
        }
        Instant now = Instant.now(clock);
        UpdateWrapper<SysUser> update = new UpdateWrapper<SysUser>()
                .eq("id", userId)
                .eq("deleted", false);

        if (request.getDisplayName() != null) {
            update.set("display_name", requireText(request.getDisplayName(), "displayName", "显示名",
                    MAX_DISPLAY_NAME_LENGTH));
        }
        if (request.getEmployeeNo() != null) {
            update.set("employee_no", optionalText(request.getEmployeeNo(), "employeeNo", MAX_EMPLOYEE_NO_LENGTH));
        }
        if (request.getStatus() != null) {
            boolean disabling = request.getStatus() == UserStatus.DISABLED;
            if (disabling && principal != null && userId.equals(principal.userId())) {
                // 防止管理员把自己停用后无人可管理系统（恢复需 DBA 介入）。
                throw new UserValidationException("status", "不能停用当前登录账号");
            }
            update.set("status", request.getStatus() == UserStatus.DISABLED ? 0 : 1);
        }
        if (request.getPhone() != null) {
            if (request.getPhone().isBlank()) {
                // 显式清除手机号：四列必须同时置空，满足 ck_sys_user_phone_crypto。
                update.set("phone_enc", null)
                        .set("phone_hash", null)
                        .set("phone_key_id", null)
                        .set("phone_updated_at", null);
            } else {
                PhoneWrite phone = phoneWrite(request.getPhone(), now);
                update.set("phone_enc", phone.encrypted())
                        .set("phone_hash", phone.hash())
                        .set("phone_key_id", phone.keyId())
                        .set("phone_updated_at", phone.updatedAt());
            }
        }
        update.set("updated_at", now)
                .set("updated_by", principal == null ? null : principal.userId())
                .set("version", existing.getVersion() == null ? 1L : existing.getVersion() + 1);
        int updated = userMapper.update(null, update);
        if (updated != 1) {
            throw new NoSuchElementException("用户不存在或不可见");
        }
        if (request.getRoles() != null) {
            applyRoles(userId, request.getRoles(), principal);
        }
        return toSummary(requireUser(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(action = "PASSWORD_RESET", resourceType = "USER")
    public PasswordResetResponse resetPassword(AuthPrincipal principal, UUID userId, AuthRequestContext context) {
        SysUser user = requireUser(userId);
        String initialPassword = generateInitialPassword(user.getUsername());
        int updated = userMapper.adminResetPassword(userId, passwordEncoder.encode(initialPassword),
                principal == null ? null : principal.userId());
        if (updated != 1) {
            throw new NoSuchElementException("用户不存在或不可见");
        }
        // S-4：管理员重置口令必然意味着账号可能已泄露/口令由他人掌握，同一事务内撤销
        // 目标用户的全部刷新令牌，使其既有会话立即失效；访问令牌按 15 分钟自然过期，
        // 刷新路径因令牌已撤销直接 401。
        refreshTokenMapper.revokeAllByUserId(userId);
        PasswordResetResponse response = new PasswordResetResponse();
        response.setUserId(userId.toString());
        // 一次性初始口令只在本响应出现一次：不入库明文、不入日志、不入审计。
        response.setInitialPassword(initialPassword);
        response.setMustChangePassword(true);
        response.setPasswordResetAt(Instant.now(clock).atOffset(ZoneOffset.UTC));
        return response;
    }

    private SysUser requireUser(UUID userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new NoSuchElementException("用户不存在或不可见");
        }
        return user;
    }

    private boolean usernameExists(String username) {
        Long count = userMapper.selectCount(new QueryWrapper<SysUser>()
                .apply("lower(username) = lower({0})", username));
        return count != null && count > 0;
    }

    /**
     * 重写用户角色（先删后插，事务内保证不出现半套角色）。
     *
     * <p>S-10 提权面：操作人只能授予自己权限点集合内的角色。按“角色权限点 ⊆ 操作人权限点”
     * 判断，超集一律 403 并写 {@code ACCESS_DENIED} 审计；校验在任何写操作之前完成。
     */
    private void applyRoles(UUID userId, List<String> roleCodes, AuthPrincipal principal) {
        UUID actorId = principal == null ? null : principal.userId();
        if (roleCodes == null || roleCodes.isEmpty()) {
            userMapper.deleteUserRoles(userId);
            return;
        }
        Set<String> codes = new LinkedHashSet<>();
        for (String raw : roleCodes) {
            if (raw != null && !raw.isBlank()) {
                codes.add(raw.trim());
            }
        }
        if (codes.isEmpty()) {
            userMapper.deleteUserRoles(userId);
            return;
        }
        List<SysRole> found = roleMapper.selectList(new QueryWrapper<SysRole>().in("code", codes));
        Set<String> known = new LinkedHashSet<>();
        for (SysRole role : found) {
            known.add(role.getCode());
        }
        for (String code : codes) {
            if (!known.contains(code)) {
                throw new UserValidationException("roles", "角色编码不存在：" + code);
            }
        }
        requireGrantablePermissions(codes, principal);
        userMapper.deleteUserRoles(userId);
        for (String code : codes) {
            userMapper.insertUserRoleByCode(userId, code, actorId);
        }
    }

    private void requireGrantablePermissions(Set<String> roleCodes, AuthPrincipal principal) {
        if (principal == null || principal.userId() == null) {
            return;
        }
        Set<String> actorPermissions = new HashSet<>(permissionMapper.selectCodesByUserId(principal.userId()));
        List<String> requestedPermissions = permissionMapper.selectCodesByRoleCodes(roleCodes);
        for (String permission : requestedPermissions) {
            if (!actorPermissions.contains(permission)) {
                // 不把缺失的权限点写进响应/审计 detail；异常由 Web 层映射为 403 并记 ACCESS_DENIED。
                throw new AccessDeniedException("不能授予操作人自身不具备的权限");
            }
        }
    }

    private PhoneWrite phoneWrite(String rawPhone, Instant now) {
        String phone = readMobile(rawPhone);
        return new PhoneWrite(phoneCrypto.encrypt(phone), phoneHasher.hash(phone),
                phoneCrypto.keyId(), now);
    }

    /** 规范化并校验手机号（非中国内地号码不做猜测性转换）。 */
    private static String readMobile(String rawPhone) {
        String phone = PhoneHasher.normalize(rawPhone);
        if (!isMobile(phone)) {
            throw new UserValidationException("phone", PHONE_FORMAT_MESSAGE);
        }
        return phone;
    }

    private static boolean isMobile(String value) {
        return value != null && value.matches(MOBILE_PATTERN);
    }

    private UserSummary toSummary(SysUser user) {
        UserSummary summary = new UserSummary();
        summary.setUserId(user.getId().toString());
        summary.setUsername(user.getUsername());
        summary.setDisplayName(user.getDisplayName());
        summary.setStatus(toContractStatus(user.getStatus()));
        summary.setPhoneMasked(maskedPhone(user));
        summary.setRoles(new ArrayList<>(roleMapper.selectCodesByUserId(user.getId())));
        summary.setMustChangePassword(user.getMustChangePassword() == null || user.getMustChangePassword());
        summary.setCreatedAt(user.getCreatedAt() == null
                ? null
                : user.getCreatedAt().atOffset(ZoneOffset.UTC));
        return summary;
    }

    /**
     * 解密并脱敏手机号；密钥版本不匹配或密文损坏时降级为不展示（不阻断列表/详情）。
     * 日志只记用户标识与固定文案，不含密文或明文。
     */
    private String maskedPhone(SysUser user) {
        try {
            return phoneCrypto.masked(user.getPhoneEnc(), user.getPhoneKeyId());
        } catch (PhoneCryptoException e) {
            LOG.warn("手机号密文不可解密，已降级为不展示：userId={}，原因={}",
                    user.getId(), e.getMessage());
            return null;
        }
    }

    private static UserStatus toContractStatus(Integer status) {
        return status != null && status == 0 ? UserStatus.DISABLED : UserStatus.ACTIVE;
    }

    private static String requireText(String raw, String field, String fieldName, int maxLength) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new UserValidationException(field, fieldName + "不能为空");
        }
        if (value.length() > maxLength) {
            throw new UserValidationException(field, fieldName + "长度不能超过 " + maxLength + " 个字符");
        }
        return value;
    }

    private static String optionalText(String raw, String field, int maxLength) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > maxLength) {
            throw new UserValidationException(field, "字段长度不能超过 " + maxLength + " 个字符");
        }
        return value;
    }

    /**
     * 生成一次性初始口令：随机 15 字节 Base64URL + 固定前缀 {@code An9!}，
     * 保证同时含大写/小写/数字/符号四类且长度 ≥12；偶发包含登录账号时重试。
     */
    private String generateInitialPassword(String username) {
        for (int attempt = 0; attempt < MAX_GENERATE_ATTEMPTS; attempt++) {
            byte[] raw = new byte[GENERATED_PASSWORD_BYTES];
            secureRandom.nextBytes(raw);
            String candidate = "An9!" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            if (candidate.length() < MIN_PASSWORD_LENGTH) {
                continue;
            }
            try {
                PasswordPolicy.validate(candidate, username, null);
                return candidate;
            } catch (IllegalArgumentException ignored) {
                // 随机串碰巧包含登录账号：重新生成（概率极低，最多 5 次）。
            }
        }
        throw new IllegalStateException("初始口令生成失败");
    }

    /** 一次手机号写入产生的四列值。 */
    private record PhoneWrite(String encrypted, String hash, String keyId, Instant updatedAt) {
    }
}
