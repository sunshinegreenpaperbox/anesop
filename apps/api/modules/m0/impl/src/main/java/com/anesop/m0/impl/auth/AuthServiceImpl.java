package com.anesop.m0.impl.auth;

import com.anesop.contracts.auth.model.ChangePasswordRequest;
import com.anesop.contracts.auth.model.CurrentUser;
import com.anesop.contracts.auth.model.DataScope;
import com.anesop.contracts.auth.model.LoginRequest;
import com.anesop.contracts.auth.model.PageMeta;
import com.anesop.contracts.auth.model.RefreshRequest;
import com.anesop.contracts.auth.model.Session;
import com.anesop.contracts.auth.model.SessionPage;
import com.anesop.contracts.auth.model.TokenResponse;
import com.anesop.m0.api.audit.Audited;
import com.anesop.m0.api.security.AccessTokenIssuer;
import com.anesop.m0.api.security.AccessTokenRevocationStore;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.AuthService;
import com.anesop.m0.api.security.AuthUser;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m0.api.security.PasswordPolicy;
import com.anesop.m0.impl.persistence.entity.SysRefreshToken;
import com.anesop.m0.impl.persistence.entity.SysUser;
import com.anesop.m0.impl.persistence.mapper.SysPermissionMapper;
import com.anesop.m0.impl.persistence.mapper.SysRefreshTokenMapper;
import com.anesop.m0.impl.persistence.mapper.SysRoleMapper;
import com.anesop.m0.impl.persistence.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 认证与令牌服务实现（契约 auth.yaml 0.3.0）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>访问令牌：JWT（由 {@link AccessTokenIssuer} 在 api-bootstrap 签发），默认 15 分钟；</li>
 *   <li>刷新令牌：256 位随机值，DB 只存 SHA-256 哈希；刷新即轮换，旧令牌条件更新撤销；</li>
 *   <li>登出：按会话（JWT sid）撤销刷新令牌，并把 jti 写入吊销表使访问令牌立即失效；</li>
 *   <li>失败处理：口令错误统一 401，连续 5 次锁定 30 分钟（§3.9.1），计数用单条原子 UPDATE
 *       （B-3，避免并发丢失更新）；锁定过期后清零（S-3）；</li>
 *   <li>侧信道：账号不存在时同样执行一次 BCrypt 比对（固定假哈希），与存在账号同成本；</li>
 *   <li>强制改密：{@code must_change_password=true} 的账号由 Web 层限制可用接口（S-3）。</li>
 * </ul>
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int REFRESH_TOKEN_BYTES = 32;
    private static final int MAX_CLIENT_INFO = 512;

    /** 固定假哈希（cost=10），仅用于账号不存在时制造同等 BCrypt 计算成本，不对应任何真实账号。 */
    private static final String DUMMY_PASSWORD_HASH =
            "$2y$10$Pitqi0NOQHfCCOT2N0.Kau6Z3iFioZZ05ytAmkQzAQDhdGNiROqO2";

    /** 凭据错误统一文案（不区分账号是否存在）。 */
    private static final String CREDENTIALS_MESSAGE = "账号或口令错误";

    /** 账号状态类统一模糊文案（S-2：停用/锁定/非本地认证不暴露账号状态）。 */
    private static final String ACCOUNT_STATE_MESSAGE = "账号状态或认证方式不允许登录";

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysRefreshTokenMapper refreshTokenMapper;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokenIssuer;
    private final AccessTokenRevocationStore revocationStore;
    private final DataScopeResolver dataScopeResolver;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration refreshTtl;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthServiceImpl(SysUserMapper userMapper,
            SysRoleMapper roleMapper,
            SysPermissionMapper permissionMapper,
            SysRefreshTokenMapper refreshTokenMapper,
            PasswordEncoder passwordEncoder,
            AccessTokenIssuer accessTokenIssuer,
            AccessTokenRevocationStore revocationStore,
            DataScopeResolver dataScopeResolver,
            ObjectMapper objectMapper,
            Clock authClock,
            @Value("${anesop.security.jwt.refresh-ttl:P7D}") String refreshTtl) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.refreshTokenMapper = refreshTokenMapper;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenIssuer = accessTokenIssuer;
        this.revocationStore = revocationStore;
        this.dataScopeResolver = dataScopeResolver;
        // 防御性拷贝：不持有外部可变 ObjectMapper（SpotBugs EI_EXPOSE_REP2）。
        this.objectMapper = objectMapper.copy();
        this.clock = authClock;
        this.refreshTtl = Duration.parse(refreshTtl);
    }

    @Override
    @Audited(action = "LOGIN", resourceType = "USER")
    public TokenResponse login(LoginRequest request, AuthRequestContext context) {
        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        String rawPassword = request.getPassword() == null ? "" : request.getPassword();
        // 登录名大小写不敏感（V1 唯一索引 lower(username)），统一按小写匹配。
        SysUser user = userMapper.selectOne(new QueryWrapper<SysUser>()
                .apply("lower(username) = lower({0})", username));

        if (user == null) {
            // 侧信道防护：账号不存在也执行一次 BCrypt 比对（固定假哈希），与存在账号同成本。
            passwordEncoder.matches(rawPassword, DUMMY_PASSWORD_HASH);
            throw new BadCredentialsException(CREDENTIALS_MESSAGE);
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new DisabledException(ACCOUNT_STATE_MESSAGE);
        }
        Instant now = Instant.now(clock);
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            throw new LockedException(ACCOUNT_STATE_MESSAGE);
        }
        if (user.getLockedUntil() != null) {
            // 锁定已过期：先原子清零，避免旧计数让下一次失败立即再次锁定（S-3）。
            userMapper.resetExpiredLock(user.getId());
        }
        if (request.getAuthType() != null && request.getAuthType() != LoginRequest.AuthTypeEnum.LOCAL) {
            // AD/LDAP/SSO 为外部依赖（BLOCKED-EXTERNAL EXT-05），S0 只开通本地认证。
            throw new DisabledException(ACCOUNT_STATE_MESSAGE);
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            // 单条原子 UPDATE 累加并置锁，不做读-改-写（B-3）。
            userMapper.registerFailedAttempt(user.getId());
            throw new BadCredentialsException(CREDENTIALS_MESSAGE);
        }

        markLoginSuccess(user, now);
        AuthUser authUser = loadAuthUser(user);
        CreatedRefreshToken refreshToken = createRefreshToken(user.getId(), context);
        AccessTokenIssuer.IssuedAccessToken accessToken = accessTokenIssuer.issue(authUser, refreshToken.sessionId());
        return tokenResponse(authUser, accessToken, refreshToken.token());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @Audited(action = "TOKEN_REFRESH", resourceType = "SESSION")
    public TokenResponse refresh(RefreshRequest request, AuthRequestContext context) {
        Instant now = Instant.now(clock);
        String tokenHash = TokenHashing.sha256(request.getRefreshToken());
        SysRefreshToken stored = refreshTokenMapper.selectOne(new LambdaQueryWrapper<SysRefreshToken>()
                .eq(SysRefreshToken::getTokenHash, tokenHash));
        if (stored == null || stored.getRevokedAt() != null || !stored.getExpiresAt().isAfter(now)) {
            throw new BadCredentialsException("刷新令牌无效或已过期");
        }

        // 条件撤销保证并发刷新只有一个成功：旧令牌被使用一次后立即失效。
        int revoked = refreshTokenMapper.update(null, new UpdateWrapper<SysRefreshToken>()
                .eq("id", stored.getId())
                .isNull("revoked_at")
                .set("revoked_at", now));
        if (revoked != 1) {
            throw new BadCredentialsException("刷新令牌已被使用");
        }

        SysUser user = userMapper.selectById(stored.getUserId());
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new BadCredentialsException("账号不可用");
        }

        AuthUser authUser = loadAuthUser(user);
        CreatedRefreshToken rotated = createRefreshToken(user.getId(), context);
        AccessTokenIssuer.IssuedAccessToken accessToken = accessTokenIssuer.issue(authUser, rotated.sessionId());
        return tokenResponse(authUser, accessToken, rotated.token());
    }

    @Override
    @Audited(action = "LOGOUT", resourceType = "SESSION")
    public void logout(AuthPrincipal principal, AuthRequestContext context) {
        Instant now = Instant.now(clock);
        if (principal.sessionId() != null) {
            // 只撤销当前会话；条件更新保证幂等（重复登出不报错）。
            refreshTokenMapper.update(null, new UpdateWrapper<SysRefreshToken>()
                    .eq("id", parseUuid(principal.sessionId()))
                    .eq("user_id", principal.userId())
                    .isNull("revoked_at")
                    .set("revoked_at", now));
        }
        revocationStore.revoke(principal.tokenId(), principal.tokenExpiresAt());
    }

    @Override
    @Audited(action = "PASSWORD_CHANGE", resourceType = "USER")
    public void changePassword(AuthPrincipal principal, ChangePasswordRequest request, AuthRequestContext context) {
        SysUser user = userMapper.selectById(principal.userId());
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new BadCredentialsException(CREDENTIALS_MESSAGE);
        }
        String oldPassword = request.getOldPassword() == null ? "" : request.getOldPassword();
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BadCredentialsException("旧口令错误");
        }
        PasswordPolicy.validate(request.getNewPassword(), user.getUsername(), oldPassword);
        int updated = userMapper.updatePassword(user.getId(), passwordEncoder.encode(request.getNewPassword()));
        if (updated != 1) {
            throw new BadCredentialsException(CREDENTIALS_MESSAGE);
        }
    }

    @Override
    public CurrentUser currentUser(UUID userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            throw new BadCredentialsException("用户不存在或已停用");
        }
        return toCurrentUser(loadAuthUser(user));
    }

    @Override
    public SessionPage sessions(UUID userId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        Long counted = refreshTokenMapper.selectCount(new LambdaQueryWrapper<SysRefreshToken>()
                .eq(SysRefreshToken::getUserId, userId));
        long total = counted == null ? 0 : counted;
        long offset = (long) (safePage - 1) * safeSize;

        List<Session> items = new ArrayList<>();
        if (total > offset) {
            // 分页参数已钳制为 int（page>=1, 1<=size<=200），拼接 LIMIT/OFFSET 无注入风险。
            List<SysRefreshToken> rows = refreshTokenMapper.selectList(new LambdaQueryWrapper<SysRefreshToken>()
                    .eq(SysRefreshToken::getUserId, userId)
                    .orderByDesc(SysRefreshToken::getIssuedAt)
                    .last("LIMIT " + safeSize + " OFFSET " + offset));
            for (SysRefreshToken token : rows) {
                items.add(toSession(token));
            }
        }

        PageMeta meta = new PageMeta();
        meta.setPage(safePage);
        meta.setSize(safeSize);
        meta.setTotal((int) Math.min(Integer.MAX_VALUE, total));
        meta.setTotalPages(total == 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (total + safeSize - 1) / safeSize));

        SessionPage sessionPage = new SessionPage();
        sessionPage.setItems(items);
        sessionPage.setMeta(meta);
        return sessionPage;
    }

    private void markLoginSuccess(SysUser user, Instant now) {
        userMapper.update(null, new UpdateWrapper<SysUser>()
                .eq("id", user.getId())
                .set("failed_attempts", 0)
                .set("locked_until", null)
                .set("last_login_at", now)
                .set("updated_at", now));
    }

    private AuthUser loadAuthUser(SysUser user) {
        List<String> roles = roleMapper.selectCodesByUserId(user.getId());
        List<String> permissions = permissionMapper.selectCodesByUserId(user.getId());
        DataScopeAssignments dataScopes = dataScopeResolver.resolve(user.getId());
        boolean mustChangePassword = user.getMustChangePassword() == null || user.getMustChangePassword();
        // siteIds 取 SITE 绑定（M2-M0-01）：ALL/无 SITE 绑定时为空数组，表示不按院区收窄。
        List<String> siteIds = dataScopes.assignments().stream()
                .filter(assignment -> assignment.type() == com.anesop.m0.api.security.DataScope.SITE)
                .flatMap(assignment -> assignment.scopeIds().stream())
                .toList();
        return new AuthUser(user.getId(), user.getUsername(), user.getDisplayName(), null,
                mustChangePassword, roles, permissions, dataScopes.assignments(), siteIds);
    }

    private CreatedRefreshToken createRefreshToken(UUID userId, AuthRequestContext context) {
        byte[] raw = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant now = Instant.now(clock);

        SysRefreshToken row = new SysRefreshToken();
        row.setId(UUID.randomUUID());
        row.setUserId(userId);
        row.setTokenHash(TokenHashing.sha256(token));
        row.setIssuedAt(now);
        row.setExpiresAt(now.plus(refreshTtl));
        row.setClientInfo(clientInfoJson(context));
        refreshTokenMapper.insert(row);
        return new CreatedRefreshToken(token, row.getId().toString());
    }

    private TokenResponse tokenResponse(AuthUser user,
            AccessTokenIssuer.IssuedAccessToken accessToken,
            String refreshToken) {
        // 向上取整：签发与序列化之间的毫秒差不能把 900 秒显示成 899。
        long remainingMillis = Duration.between(Instant.now(clock), accessToken.expiresAt()).toMillis();
        long expiresIn = Math.max(1, (remainingMillis + 999) / 1000);
        TokenResponse response = new TokenResponse();
        response.setAccessToken(accessToken.token());
        response.setRefreshToken(refreshToken);
        response.setTokenType(TokenResponse.TokenTypeEnum.BEARER);
        response.setExpiresIn((int) Math.max(1, expiresIn));
        response.setUser(toCurrentUser(user));
        return response;
    }

    private CurrentUser toCurrentUser(AuthUser user) {
        CurrentUser currentUser = new CurrentUser();
        currentUser.setUserId(user.userId().toString());
        currentUser.setUsername(user.username());
        currentUser.setDisplayName(user.displayName());
        currentUser.setStaffId(user.staffId());
        currentUser.setMustChangePassword(user.mustChangePassword());
        currentUser.setRoles(new ArrayList<>(user.roles()));
        currentUser.setPermissions(new ArrayList<>(user.permissions()));
        // NONE 是内部拒绝标记，不输出到契约；表现为空 dataScopes（无任何数据权限）。
        currentUser.setDataScopes(user.dataScopes().stream()
                .filter(assignment -> assignment.type() != com.anesop.m0.api.security.DataScope.NONE)
                .map(this::toContractScope)
                .toList());
        currentUser.setSiteIds(new ArrayList<>(user.siteIds()));
        return currentUser;
    }

    private DataScope toContractScope(DataScopeAssignment assignment) {
        DataScope scope = new DataScope();
        scope.setType(switch (assignment.type()) {
            case ALL -> com.anesop.contracts.auth.model.DataScope.TypeEnum.ALL;
            case SITE -> com.anesop.contracts.auth.model.DataScope.TypeEnum.SITE;
            case DEPT -> com.anesop.contracts.auth.model.DataScope.TypeEnum.DEPARTMENT;
            case ROOM -> com.anesop.contracts.auth.model.DataScope.TypeEnum.ROOM;
            case SELF -> com.anesop.contracts.auth.model.DataScope.TypeEnum.OWN_PATIENTS;
            case NONE -> throw new IllegalStateException("NONE 拒绝标记不应输出到契约");
        });
        scope.setScopeIds(new ArrayList<>(assignment.scopeIds()));
        return scope;
    }

    private Session toSession(SysRefreshToken token) {
        Session session = new Session();
        session.setSessionId(token.getId().toString());
        session.setUserId(token.getUserId().toString());
        ClientInfo clientInfo = parseClientInfo(token.getClientInfo());
        session.setClientIp(clientInfo.ip());
        session.setUserAgent(clientInfo.userAgent());
        session.setLoginAt(token.getIssuedAt().atOffset(ZoneOffset.UTC));
        session.setExpiresAt(token.getExpiresAt().atOffset(ZoneOffset.UTC));
        session.setStatus(statusOf(token));
        session.setCreatedAt(token.getIssuedAt().atOffset(ZoneOffset.UTC));
        session.setCreatedBy(token.getUserId().toString());
        session.setUpdatedAt(token.getIssuedAt().atOffset(ZoneOffset.UTC));
        session.setUpdatedBy(token.getUserId().toString());
        session.setVersion(1);
        return session;
    }

    private Session.StatusEnum statusOf(SysRefreshToken token) {
        if (token.getRevokedAt() != null) {
            return Session.StatusEnum.REVOKED;
        }
        if (!token.getExpiresAt().isAfter(Instant.now(clock))) {
            return Session.StatusEnum.EXPIRED;
        }
        return Session.StatusEnum.ACTIVE;
    }

    private String clientInfoJson(AuthRequestContext context) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("ip", context == null || context.clientIp() == null ? "" : context.clientIp());
        info.put("userAgent", context == null || context.userAgent() == null ? "" : context.userAgent());
        try {
            String json = objectMapper.writeValueAsString(info);
            return json.length() <= MAX_CLIENT_INFO ? json : json.substring(0, MAX_CLIENT_INFO);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private ClientInfo parseClientInfo(String json) {
        if (json == null || json.isBlank()) {
            return new ClientInfo("", "");
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return new ClientInfo(textOrEmpty(node, "ip"), textOrEmpty(node, "userAgent"));
        } catch (JsonProcessingException e) {
            // 历史/异常格式不阻断会话展示，按空值处理。
            return new ClientInfo("", "");
        }
    }

    private static String textOrEmpty(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 新建的刷新令牌：明文只返回给客户端一次，DB 只保留哈希。 */
    private record CreatedRefreshToken(String token, String sessionId) {
    }

    private record ClientInfo(String ip, String userAgent) {
    }
}
