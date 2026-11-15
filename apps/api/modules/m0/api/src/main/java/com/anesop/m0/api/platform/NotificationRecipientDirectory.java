package com.anesop.m0.api.platform;

import java.util.List;
import java.util.UUID;

/**
 * 通知接收人目录（M9-01，内部只读服务；不暴露 HTTP 端点）。
 *
 * <p>业务模块（m1 通知派发、m2 严重告警）按<b>角色编码</b>解析站内消息接收人：
 * 只返回未删除且 {@code status=1}（启用）的用户，不返回姓名、手机号、科室等任何属性。
 * 收件人判定完全由 M0 的角色绑定决定，调用模块不感知用户表结构（不跨模块 Mapper）。
 *
 * <p>没有绑定该角色的启用用户时返回空列表（调用方按「无接收人」处理并记录元数据日志，
 * 不重试、不广播给其他角色）。
 */
public interface NotificationRecipientDirectory {

    /**
     * 某角色下的启用用户标识（升序）。
     *
     * @param roleCode 角色编码（如 SYS_ANESTHESIOLOGIST、SYS_OR_MANAGER）；非法格式返回空列表
     */
    List<UUID> enabledUserIdsByRole(String roleCode);
}
