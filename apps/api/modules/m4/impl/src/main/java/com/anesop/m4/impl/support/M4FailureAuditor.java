package com.anesop.m4.impl.support;

import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 药品/麻精五专失败路径审计（M4-M15-01 S-2）。
 *
 * <p>目录维护与五专登记返回 400/409/422 时业务事务会回滚；若在同一事务内写审计，失败留痕
 * 也会被回滚。本组件用 {@code REQUIRES_NEW} 挂起当前事务、以独立事务写 {@code result=0}
 * 审计，保证失败可追溯，同时不改变业务失败语义。
 *
 * <p>detail 由调用方按白名单构造（operation/field/rejection/标识），不得包含处方号原文、
 * 人员姓名、药品备注原文或患者信息；审计写入失败只记服务端日志，不覆盖原始业务异常。
 */
@Component
public class M4FailureAuditor {

    private static final Logger LOG = LoggerFactory.getLogger(M4FailureAuditor.class);

    private final AuditWriter auditWriter;

    public M4FailureAuditor(AuditWriter auditWriter) {
        this.auditWriter = auditWriter;
    }

    /**
     * 在独立事务中写一条失败审计（{@code result=0}）。
     *
     * @param detail 已按白名单构造的明细；本方法防御性复制
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String action, String resourceType, String resourceId,
            Map<String, Object> detail, AuthPrincipal caller, AuthRequestContext context) {
        Map<String, Object> safeDetail = detail == null ? Map.of() : new LinkedHashMap<>(detail);
        try {
            auditWriter.record(action, resourceType, resourceId, false, safeDetail,
                    caller == null ? null : caller.userId(),
                    caller == null ? null : caller.username(), context);
        } catch (RuntimeException auditFailure) {
            // 审计失败不得覆盖原始业务异常（原始 400/409/422 仍返回给调用方）。
            LOG.error("M4 失败路径审计落库失败：action={} resourceType={} resourceId={}",
                    action, resourceType, resourceId, auditFailure);
        }
    }
}
