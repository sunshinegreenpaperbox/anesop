package com.anesop.m0.impl.persistence.mapper;

import com.anesop.m0.impl.persistence.entity.SysAuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计日志 Mapper（m0_platform.sys_audit_log）。
 *
 * <p>append-only 约束由数据库触发器保证；业务代码只允许 insert 与查询，
 * 不得新增 update/delete 方法（审计落库入口由 S0-07 的审计服务统一封装）。
 */
@Mapper
public interface SysAuditLogMapper extends BaseMapper<SysAuditLog> {
}
