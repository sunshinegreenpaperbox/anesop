package com.anesop.m2.impl.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.anesop.contracts.auth.model.CurrentUser;
import com.anesop.contracts.auth.model.UserStatus;
import com.anesop.contracts.auth.model.UserSummary;
import com.anesop.m0.api.security.AuthService;
import com.anesop.m0.api.security.UserAdminService;
import com.anesop.m2.api.error.M2ValidationException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.BadCredentialsException;

/**
 * 麻精第二核对人资格单测（M9-01）：缺失/同人/不存在/停用/无权限/成功，
 * 与记录单用药同一口径（复用组件被抢救记录调用）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SecondCheckerEligibilityTest {

    private static final UUID ACTOR = UUID.fromString("e0000000-0000-4000-8000-000000000002");
    private static final UUID CHECKER = UUID.fromString("e0000000-0000-4000-8000-000000000003");

    @Mock
    private UserAdminService userAdminService;
    @Mock
    private AuthService authService;

    private SecondCheckerEligibility eligibility;

    @BeforeEach
    void setUp() {
        eligibility = new SecondCheckerEligibility(userAdminService, authService);
    }

    @Test
    void missingIdOrNameIsRejected() {
        assertThatThrownBy(() -> eligibility.require(null, "演示核对人", ACTOR, "drugs[0]"))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("第二核对人");
        assertThatThrownBy(() -> eligibility.require(CHECKER, " ", ACTOR, "drugs[0]"))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("姓名");
    }

    @Test
    void samePersonIsRejected() {
        assertThatThrownBy(() -> eligibility.require(ACTOR, "演示核对人", ACTOR, "drugs[0]"))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("同一人");
    }

    @Test
    void missingUserIsRejected() {
        when(userAdminService.get(CHECKER)).thenThrow(new NoSuchElementException("missing"));

        assertThatThrownBy(() -> eligibility.require(CHECKER, "演示核对人", ACTOR, "drugs[0]"))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("不存在或已停用");
    }

    @Test
    void disabledUserIsRejected() {
        when(userAdminService.get(CHECKER)).thenReturn(user(UserStatus.DISABLED));

        assertThatThrownBy(() -> eligibility.require(CHECKER, "演示核对人", ACTOR, "drugs[0]"))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("不存在或已停用");
    }

    @Test
    void disabledPermissionSnapshotIsRejectedFailClosed() {
        when(userAdminService.get(CHECKER)).thenReturn(user(UserStatus.ACTIVE));
        when(authService.currentUser(CHECKER)).thenThrow(new BadCredentialsException("denied"));

        assertThatThrownBy(() -> eligibility.require(CHECKER, "演示核对人", ACTOR, "drugs[0]"))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("不存在或已停用");
    }

    @Test
    void checkerWithoutRecordWritePermissionIsRejected() {
        when(userAdminService.get(CHECKER)).thenReturn(user(UserStatus.ACTIVE));
        CurrentUser snapshot = new CurrentUser();
        snapshot.setPermissions(List.of("m2:record:read"));
        when(authService.currentUser(CHECKER)).thenReturn(snapshot);

        assertThatThrownBy(() -> eligibility.require(CHECKER, "演示核对人", ACTOR, "drugs[0]"))
                .isInstanceOf(M2ValidationException.class)
                .hasMessageContaining("无麻醉记录书写权限");
    }

    @Test
    void eligibleCheckerReturnsNormalizedName() {
        when(userAdminService.get(CHECKER)).thenReturn(user(UserStatus.ACTIVE));
        CurrentUser snapshot = new CurrentUser();
        snapshot.setPermissions(List.of("m2:record:read", "m2:record:write"));
        when(authService.currentUser(CHECKER)).thenReturn(snapshot);

        assertThat(eligibility.require(CHECKER, " 演示核对人 ", ACTOR, "drugs[0]"))
                .isEqualTo("演示核对人");
    }

    private static UserSummary user(UserStatus status) {
        UserSummary summary = new UserSummary();
        summary.setUserId(CHECKER.toString());
        summary.setStatus(status);
        summary.setUsername("synth.checker");
        summary.setDisplayName("演示核对人（合成）");
        return summary;
    }
}
