package com.anesop.m0.impl.audit;

import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.impl.persistence.entity.SysAuditLog;
import com.anesop.m0.impl.persistence.mapper.SysAuditLogMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 审计落库实现（{@link AuditWriter} 的 JDBC 版本）：唯一写 {@code sys_audit_log} 的组件
 * （append-only，只 INSERT）。
 *
 * <p>字段约定：action/resource_type/resource_id/result/detail（jsonb）/ip（inet）/
 * user_agent/trace_id/occurred_at。detail 由调用方按白名单构造，禁止口令、令牌明文与
 * 患者直接标识；IP 非法时写 NULL，避免 inet 转换失败。
 */
@Component
public class JdbcAuditWriter implements AuditWriter {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcAuditWriter.class);
    private static final int MAX_USER_AGENT = 512;
    private static final int MAX_ACTOR_NAME = 100;
    private static final int MAX_TRACE_ID = 64;

    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");

    private final SysAuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JdbcAuditWriter(SysAuditLogMapper auditLogMapper, ObjectMapper objectMapper, Clock authClock) {
        this.auditLogMapper = auditLogMapper;
        // 防御性拷贝：不持有外部可变 ObjectMapper（SpotBugs EI_EXPOSE_REP2），
        // 同时保留 Spring 已注册的 JavaTime/模块配置。
        this.objectMapper = objectMapper.copy();
        this.clock = authClock;
    }

    @Override
    public void record(String action,
            String resourceType,
            String resourceId,
            boolean success,
            Map<String, Object> detail,
            UUID actorId,
            String actorName,
            AuthRequestContext context) {
        SysAuditLog row = new SysAuditLog();
        row.setId(UUID.randomUUID());
        row.setAction(action);
        row.setResourceType(resourceType);
        row.setResourceId(resourceId);
        row.setResult(success ? 1 : 0);
        row.setDetail(toJson(detail));
        if (context != null) {
            row.setIp(normalizeIp(context.clientIp()));
            row.setUserAgent(truncate(context.userAgent(), MAX_USER_AGENT));
            row.setTraceId(truncate(context.traceId(), MAX_TRACE_ID));
        }
        row.setActorId(actorId);
        row.setActorName(truncate(actorName, MAX_ACTOR_NAME));
        row.setOccurredAt(Instant.now(clock));
        auditLogMapper.insert(row);
    }

    private String toJson(Map<String, Object> detail) {
        Map<String, Object> safe = detail == null ? Map.of() : new LinkedHashMap<>(detail);
        try {
            return objectMapper.writeValueAsString(safe);
        } catch (JsonProcessingException e) {
            // 审计明细序列化失败不能静默：用最小结构兜底并记录，避免整条审计丢失。
            LOG.error("审计明细序列化失败，降级为最小结构", e);
            return "{\"detailSerializationError\":true}";
        }
    }

    private static String normalizeIp(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        String candidate = ip.trim();
        if (IPV4.matcher(candidate).matches()) {
            return candidate;
        }
        if (candidate.indexOf(':') >= 0 && IPV6.matcher(candidate).matches()) {
            return candidate;
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
