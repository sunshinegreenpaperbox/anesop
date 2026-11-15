package com.anesop.m2.impl.support;

import com.anesop.contracts.auth.model.CurrentUser;
import com.anesop.contracts.auth.model.UserStatus;
import com.anesop.contracts.auth.model.UserSummary;
import com.anesop.m0.api.security.AuthService;
import com.anesop.m0.api.security.UserAdminService;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.api.security.M2Permissions;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;

/**
 * 麻精药品第二核对人资格校验（M9-01，m2 模块内部复用组件）。
 *
 * <p>与记录单用药（M3 T-4）同一口径：核对人必须存在、在职（status=ACTIVE）、不是给药人本人，
 * 且持有 {@code m2:record:write}。全部经 m0-api 查询（用户详情 + 实时权限快照），
 * 不访问 m0 表/Mapper；失败统一 400（不区分“不存在/停用/无权限”的对外文案差异，
 * 具体原因只用于审计与测试）。
 *
 * <p>救援记录的时间窗/用药校验由调用方负责；本组件只做人员资格，不写库、不留 PHI。
 */
@Component
public class SecondCheckerEligibility {

    private static final int MAX_NAME = 64;

    private final UserAdminService userAdminService;
    private final AuthService authService;

    public SecondCheckerEligibility(UserAdminService userAdminService, AuthService authService) {
        this.userAdminService = userAdminService;
        this.authService = authService;
    }

    /**
     * 校验并返回规范化姓名。
     *
     * @param checkerId   核对人用户标识（必填）
     * @param checkerName 核对人姓名（必填，1–64）
     * @param actorId     当前操作人（=给药人；与核对人相同时拒绝）
     * @param field       报错字段前缀（如 {@code drugs[0].secondCheckerId}）
     * @throws M2ValidationException 缺失/同人/不存在/停用/无权限
     */
    public String require(UUID checkerId, String checkerName, UUID actorId, String field) {
        if (checkerId == null) {
            throw new M2ValidationException(field, "麻精药品必须填写第二核对人");
        }
        String name = checkerName == null ? "" : checkerName.trim();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw new M2ValidationException(field, "第二核对人姓名不能为空且不超过 64 字符");
        }
        if (actorId != null && checkerId.equals(actorId)) {
            throw new M2ValidationException(field, "核对人不得与给药人为同一人");
        }
        UserSummary checker;
        try {
            checker = userAdminService.get(checkerId);
        } catch (NoSuchElementException missing) {
            throw new M2ValidationException(field, "第二核对人不存在或已停用");
        }
        if (checker.getStatus() != UserStatus.ACTIVE) {
            throw new M2ValidationException(field, "第二核对人不存在或已停用");
        }
        CurrentUser snapshot;
        try {
            snapshot = authService.currentUser(checkerId);
        } catch (BadCredentialsException denied) {
            throw new M2ValidationException(field, "第二核对人不存在或已停用");
        }
        List<String> permissions = snapshot.getPermissions();
        if (permissions == null || !permissions.contains(M2Permissions.RECORD_WRITE)) {
            throw new M2ValidationException(field, "第二核对人无麻醉记录书写权限");
        }
        return name;
    }
}
