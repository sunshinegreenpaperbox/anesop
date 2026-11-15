package com.anesop.m0.impl.platform;

import com.anesop.m0.api.platform.NotificationRecipientDirectory;
import com.anesop.m0.impl.persistence.mapper.SysUserMapper;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通知接收人目录实现（M9-01）：按角色编码查启用用户，只返回用户标识。
 *
 * <p>角色编码做格式白名单（大写字母/数字/下划线），空值或非法格式返回空列表；
 * 不访问业务模块表，不返回任何 PHI。查询走 {@code sys_user_role} 反向索引。
 */
@Service
public class NotificationRecipientDirectoryImpl implements NotificationRecipientDirectory {

    private static final Pattern ROLE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

    private final SysUserMapper userMapper;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper 由容器管理，服务不修改其内部状态")
    public NotificationRecipientDirectoryImpl(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> enabledUserIdsByRole(String roleCode) {
        if (roleCode == null || !ROLE_CODE.matcher(roleCode.trim()).matches()) {
            return List.of();
        }
        List<UUID> ids = userMapper.selectActiveUserIdsByRoleCode(roleCode.trim());
        return ids == null ? List.of() : List.copyOf(ids);
    }
}
