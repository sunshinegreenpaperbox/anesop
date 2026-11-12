package com.anesop.m0.api.audit;

import com.anesop.m0.api.security.AuthRequestContext;
import java.util.Map;
import java.util.UUID;

/**
 * 审计落库入口（m0-api 对外接口）：唯一允许写 {@code m0_platform.sys_audit_log} 的组件。
 *
 * <p>实现位于 m0-impl（{@code JdbcAuditWriter}，append-only INSERT）。本接口放在 api 层，
 * 使 api-bootstrap 的 401/403 处理器与切面只依赖 m0-api，不依赖 impl（AGENTS 模块边界）。
 *
 * <p>字段约定：action/resource_type/resource_id/result/detail（jsonb）/ip（inet）/
 * user_agent/trace_id/occurred_at。detail 由调用方按白名单构造，禁止口令、令牌明文与
 * 患者直接标识。
 */
public interface AuditWriter {

    /**
     * 写一条审计记录。
     *
     * @param success true=成功（result=1），false=失败（result=0）
     */
    void record(String action,
            String resourceType,
            String resourceId,
            boolean success,
            Map<String, Object> detail,
            UUID actorId,
            String actorName,
            AuthRequestContext context);
}
