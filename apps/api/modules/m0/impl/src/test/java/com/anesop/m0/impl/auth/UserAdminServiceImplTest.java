package com.anesop.m0.impl.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.auth.model.PasswordResetResponse;
import com.anesop.contracts.auth.model.UserCreateRequest;
import com.anesop.contracts.auth.model.UserStatus;
import com.anesop.contracts.auth.model.UserSummary;
import com.anesop.contracts.auth.model.UserSummaryPage;
import com.anesop.contracts.auth.model.UserUpdateRequest;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.UserConflictException;
import com.anesop.m0.api.security.UserValidationException;
import com.anesop.m0.impl.crypto.FieldCryptoKeyProvider;
import com.anesop.m0.impl.crypto.PhoneCrypto;
import com.anesop.m0.impl.crypto.PhoneHasher;
import com.anesop.m0.impl.persistence.entity.SysRole;
import com.anesop.m0.impl.persistence.entity.SysUser;
import com.anesop.m0.impl.persistence.mapper.SysPermissionMapper;
import com.anesop.m0.impl.persistence.mapper.SysRefreshTokenMapper;
import com.anesop.m0.impl.persistence.mapper.SysRoleMapper;
import com.anesop.m0.impl.persistence.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 用户管理服务单测（M2-M0-03）：列表分页/手机号精确查询、创建校验与冲突、角色授予的提权面、
 * 更新/清除手机号、管理员重置口令与会话撤销、密文损坏降级。
 *
 * <p>手机号走真实 {@link PhoneCrypto}/{@link PhoneHasher}（密钥为测试随机值），
 * 只 mock Mapper 与口令编码器，不 mock 被测对象。
 */
class UserAdminServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
    private static final UUID ACTOR_ID = UUID.fromString("10000000-0000-0000-0000-0000000000aa");
    private static final UUID USER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    private SysUserMapper userMapper;
    private SysRoleMapper roleMapper;
    private SysPermissionMapper permissionMapper;
    private SysRefreshTokenMapper refreshTokenMapper;
    private PasswordEncoder passwordEncoder;
    private PhoneCrypto phoneCrypto;
    private PhoneHasher phoneHasher;
    private UserAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        roleMapper = mock(SysRoleMapper.class);
        permissionMapper = mock(SysPermissionMapper.class);
        refreshTokenMapper = mock(SysRefreshTokenMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(anyString())).thenReturn("$2y$10$synthetic");
        when(userMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(roleMapper.selectCodesByUserId(any(UUID.class))).thenReturn(List.of("SYS_ADMIN"));
        when(permissionMapper.selectCodesByUserId(any(UUID.class)))
                .thenReturn(List.of("m0:user:read", "m0:user:write"));
        when(permissionMapper.selectCodesByRoleCodes(any()))
                .thenReturn(List.of("m0:user:read"));

        byte[] field = new byte[32];
        byte[] pepper = new byte[48];
        for (int i = 0; i < field.length; i++) {
            field[i] = (byte) (i * 31 + 7);
        }
        for (int i = 0; i < pepper.length; i++) {
            pepper[i] = (byte) (i * 17 + 3);
        }
        FieldCryptoKeyProvider provider = new FieldCryptoKeyProvider(
                Base64.getEncoder().encodeToString(field), "v1",
                Base64.getEncoder().encodeToString(pepper), false);
        phoneCrypto = new PhoneCrypto(provider);
        phoneHasher = new PhoneHasher(provider);

        service = new UserAdminServiceImpl(userMapper, roleMapper, permissionMapper, refreshTokenMapper,
                phoneCrypto, phoneHasher, passwordEncoder, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AuthPrincipal actor() {
        return new AuthPrincipal(ACTOR_ID, "synth_admin", "jti", NOW.plusSeconds(900), null);
    }

    private static SysUser user(int status) {
        SysUser user = new SysUser();
        user.setId(USER_ID);
        user.setUsername("synth_user");
        user.setDisplayName("合成用户");
        user.setStatus(status);
        user.setMustChangePassword(false);
        user.setVersion(2L);
        user.setCreatedAt(NOW.minusSeconds(3600));
        return user;
    }

    private static SysRole role(String code) {
        SysRole role = new SysRole();
        role.setCode(code);
        return role;
    }

    @Test
    void listWithEmptyKeywordClampsPagingAndReturnsEmptyPage() {
        UserSummaryPage page = service.list("  ", 0, 10_000);

        assertThat(page.getMeta().getPage()).isEqualTo(1);
        assertThat(page.getMeta().getSize()).isEqualTo(200);
        assertThat(page.getMeta().getTotal()).isZero();
        assertThat(page.getMeta().getTotalPages()).isZero();
        assertThat(page.getItems()).isEmpty();
    }

    @Test
    void listByMobileUsesHashMatchingAndMasksPhone() {
        SysUser row = user(1);
        row.setPhoneEnc(phoneCrypto.encrypt("13800005678"));
        row.setPhoneHash(phoneHasher.hash("13800005678"));
        row.setPhoneKeyId("v1");
        row.setCreatedAt(null);
        row.setMustChangePassword(null);
        when(userMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(userMapper.selectList(any(Wrapper.class))).thenReturn(List.of(row));

        UserSummaryPage page = service.list("+86 138-0000-5678", 1, 10);

        assertThat(page.getItems()).hasSize(1);
        assertThat(page.getItems().get(0).getPhoneMasked()).isEqualTo("138****5678");
        assertThat(page.getItems().get(0).getMustChangePassword()).as("null 视为强制改密").isTrue();
        assertThat(page.getItems().get(0).getCreatedAt()).isNull();
        assertThat(page.getItems().get(0).getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(page.getMeta().getTotalPages()).isEqualTo(1);
    }

    @Test
    void listByTextKeywordPaginatesSecondPage() {
        when(userMapper.selectCount(any(Wrapper.class))).thenReturn(25L);
        when(userMapper.selectList(any(Wrapper.class))).thenReturn(List.of(user(0)));

        UserSummaryPage page = service.list("synth", 2, 10);

        assertThat(page.getMeta().getPage()).isEqualTo(2);
        assertThat(page.getMeta().getTotalPages()).isEqualTo(3);
        assertThat(page.getItems()).extracting(UserSummary::getStatus).containsExactly(UserStatus.DISABLED);
    }

    @Test
    void getThrowsWhenUserMissing() {
        when(userMapper.selectById(USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.get(USER_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("用户不存在");
    }

    @Test
    void createValidatesRequiredTextAndPasswordPolicy() {
        UserCreateRequest request = createRequest();
        request.setUsername("   ");
        assertThatThrownBy(() -> service.create(actor(), request, null))
                .isInstanceOf(UserValidationException.class)
                .hasMessageContaining("登录账号不能为空");

        UserCreateRequest tooLong = createRequest();
        tooLong.setUsername("u".repeat(65));
        assertThatThrownBy(() -> service.create(actor(), tooLong, null))
                .isInstanceOf(UserValidationException.class)
                .hasMessageContaining("长度不能超过");

        UserCreateRequest noDisplay = createRequest();
        noDisplay.setDisplayName(null);
        assertThatThrownBy(() -> service.create(actor(), noDisplay, null))
                .isInstanceOf(UserValidationException.class)
                .hasMessageContaining("显示名不能为空");

        UserCreateRequest longDisplay = createRequest();
        longDisplay.setDisplayName("名".repeat(101));
        assertThatThrownBy(() -> service.create(actor(), longDisplay, null))
                .isInstanceOf(UserValidationException.class);

        UserCreateRequest weakPassword = createRequest();
        weakPassword.setInitialPassword("short");
        assertThatThrownBy(() -> service.create(actor(), weakPassword, null))
                .isInstanceOf(UserValidationException.class)
                .hasMessageContaining("长度");

        UserCreateRequest badPhone = createRequest();
        badPhone.setPhone("12345");
        assertThatThrownBy(() -> service.create(actor(), badPhone, null))
                .isInstanceOf(UserValidationException.class)
                .hasMessageContaining("手机号格式");
    }

    @Test
    void createRejectsDuplicateUsername() {
        when(userMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> service.create(actor(), createRequest(), null))
                .isInstanceOf(UserConflictException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void createWithPhoneAndRolesEncryptsAndGrants() {
        when(userMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(roleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(role("SYS_ADMIN")));

        UserSummary summary = service.create(actor(), createRequest(), null);

        assertThat(summary.getUsername()).isEqualTo("synth_user");
        assertThat(summary.getPhoneMasked()).isEqualTo("138****5678");
        assertThat(summary.getStatus()).isEqualTo(UserStatus.ACTIVE);
        verify(userMapper).insert(any(SysUser.class));
        verify(userMapper).deleteUserRoles(any(UUID.class));
        verify(userMapper).insertUserRoleByCode(any(UUID.class), eq("SYS_ADMIN"), eq(ACTOR_ID));
    }

    @Test
    void createRejectsUnknownRoleAndPrivilegeEscalation() {
        when(userMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(roleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(role("SYS_ADMIN")));

        UserCreateRequest unknownRole = createRequest();
        unknownRole.setRoles(List.of("SYS_UNKNOWN"));
        assertThatThrownBy(() -> service.create(actor(), unknownRole, null))
                .isInstanceOf(UserValidationException.class)
                .hasMessageContaining("角色编码不存在");

        when(permissionMapper.selectCodesByRoleCodes(any())).thenReturn(List.of("m0:user:write"));
        when(permissionMapper.selectCodesByUserId(any(UUID.class))).thenReturn(List.of("m0:user:read"));
        assertThatThrownBy(() -> service.create(actor(), createRequest(), null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("不能授予");
    }

    @Test
    void createWithoutRolesDeletesExistingBindings() {
        UserCreateRequest request = createRequest();
        request.setRoles(null);

        service.create(null, request, null);

        verify(userMapper).deleteUserRoles(any(UUID.class));
    }

    @Test
    void createWithBlankRoleCodesDeletesBindings() {
        UserCreateRequest request = createRequest();
        request.setRoles(List.of(" ", ""));

        service.create(null, request, null);

        verify(userMapper).deleteUserRoles(any(UUID.class));
    }

    @Test
    void updateRejectsSelfRoleChangeAndSelfDisable() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(1));
        UserUpdateRequest roles = new UserUpdateRequest();
        roles.setRoles(List.of("SYS_ADMIN"));

        assertThatThrownBy(() -> service.update(
                new AuthPrincipal(USER_ID, "synth_admin", "jti", NOW.plusSeconds(900), null),
                USER_ID, roles, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("自身的角色");

        UserUpdateRequest disable = new UserUpdateRequest();
        disable.setRoles(null);
        disable.setStatus(UserStatus.DISABLED);
        assertThatThrownBy(() -> service.update(
                new AuthPrincipal(USER_ID, "synth_admin", "jti", NOW.plusSeconds(900), null),
                USER_ID, disable, null))
                .isInstanceOf(UserValidationException.class)
                .hasMessageContaining("不能停用");
    }

    @Test
    void updateValidatesDisplayNameAndEmployeeNo() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(1));

        UserUpdateRequest blankName = new UserUpdateRequest();
        blankName.setDisplayName("  ");
        assertThatThrownBy(() -> service.update(actor(), USER_ID, blankName, null))
                .isInstanceOf(UserValidationException.class);

        UserUpdateRequest longEmployeeNo = new UserUpdateRequest();
        longEmployeeNo.setEmployeeNo("E".repeat(65));
        assertThatThrownBy(() -> service.update(actor(), USER_ID, longEmployeeNo, null))
                .isInstanceOf(UserValidationException.class)
                .hasMessageContaining("长度不能超过");
    }

    @Test
    void updateClearsPhoneWhenBlankAndUsesOptimisticVersion() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(1));
        when(userMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        UserUpdateRequest request = new UserUpdateRequest();
        request.setPhone(" ");
        request.setEmployeeNo("  ");
        request.setStatus(UserStatus.ACTIVE);

        UserSummary summary = service.update(actor(), USER_ID, request, null);

        assertThat(summary.getUserId()).isEqualTo(USER_ID.toString());
    }

    @Test
    void updateWritesEncryptedPhoneAndAppliesRoles() {
        SysUser stored = user(1);
        stored.setPhoneEnc(phoneCrypto.encrypt("13800005678"));
        stored.setPhoneKeyId("v1");
        when(userMapper.selectById(USER_ID)).thenReturn(user(1), stored);
        when(userMapper.update(any(), any(Wrapper.class))).thenReturn(1);
        when(roleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(role("SYS_ADMIN")));
        UserUpdateRequest request = new UserUpdateRequest();
        request.setPhone("138 0000 5678");
        request.setDisplayName("新显示名");
        request.setRoles(List.of("SYS_ADMIN"));

        UserSummary summary = service.update(actor(), USER_ID, request, null);

        assertThat(summary.getPhoneMasked()).isEqualTo("138****5678");
        verify(userMapper).deleteUserRoles(USER_ID);
        verify(userMapper).insertUserRoleByCode(eq(USER_ID), eq("SYS_ADMIN"), eq(ACTOR_ID));
    }

    @Test
    void updateFailsWhenNoRowUpdated() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(1));
        when(userMapper.update(any(), any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.update(actor(), USER_ID, new UserUpdateRequest(), null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("用户不存在或不可见");
    }

    @Test
    void resetPasswordRevokesSessionsAndReturnsOneTimePassword() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(1));
        when(userMapper.adminResetPassword(eq(USER_ID), anyString(), eq(ACTOR_ID))).thenReturn(1);

        PasswordResetResponse response = service.resetPassword(actor(), USER_ID, null);

        assertThat(response.getUserId()).isEqualTo(USER_ID.toString());
        assertThat(response.getInitialPassword()).hasSizeGreaterThanOrEqualTo(12);
        assertThat(response.getMustChangePassword()).isTrue();
        assertThat(response.getPasswordResetAt()).isNotNull();
        verify(refreshTokenMapper).revokeAllByUserId(USER_ID);
    }

    @Test
    void resetPasswordFailsForMissingUserAndZeroRows() {
        when(userMapper.selectById(USER_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.resetPassword(actor(), USER_ID, null))
                .isInstanceOf(NoSuchElementException.class);

        when(userMapper.selectById(USER_ID)).thenReturn(user(1));
        when(userMapper.adminResetPassword(eq(USER_ID), anyString(), eq(ACTOR_ID))).thenReturn(0);
        assertThatThrownBy(() -> service.resetPassword(actor(), USER_ID, null))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void corruptCiphertextDegradesToNullMaskedPhone() {
        SysUser row = user(1);
        row.setPhoneEnc("bm90LWEtZ2NtLWNpcGhlcnRleHQ=");
        row.setPhoneKeyId("v1");
        when(userMapper.selectById(USER_ID)).thenReturn(row);

        UserSummary summary = service.get(USER_ID);

        assertThat(summary.getPhoneMasked()).as("密钥版本匹配但密文损坏时降级不展示").isNull();
    }

    @Test
    void phoneWithUnknownKeyIdDegradesToNull() {
        SysUser row = user(1);
        row.setPhoneEnc(phoneCrypto.encrypt("13800005678"));
        row.setPhoneKeyId("v0");
        when(userMapper.selectById(USER_ID)).thenReturn(row);

        assertThat(service.get(USER_ID).getPhoneMasked()).isNull();
    }

    private static UserCreateRequest createRequest() {
        UserCreateRequest request = new UserCreateRequest();
        request.setUsername("synth_user");
        request.setDisplayName("合成用户");
        request.setInitialPassword("Synth-Passw0rd!2026");
        request.setEmployeeNo("SYNTH-E-001");
        request.setPhone("13800005678");
        request.setRoles(List.of("SYS_ADMIN"));
        return request;
    }

    @Test
    void generatedPasswordAvoidsUsernameAndStaysStrong() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(1));
        when(userMapper.adminResetPassword(eq(USER_ID), anyString(), any())).thenReturn(1);

        String password = service.resetPassword(actor(), USER_ID, null).getInitialPassword();

        assertThat(password).startsWith("An9!");
        assertThat(password.toLowerCase()).doesNotContain("synth_user");
        assertThat(password.getBytes(StandardCharsets.UTF_8)).hasSizeGreaterThanOrEqualTo(12);
    }
}
