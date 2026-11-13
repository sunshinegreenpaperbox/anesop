package com.anesop.m2.impl.support;

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
 * 临床操作失败路径审计（M2-M5-01 复审 S-4）。
 *
 * <p>知情同意签署/拒绝/撤销与三方核查录入在返回 409/422 时，业务事务会回滚；若在同一事务内
 * 写审计，审计也会被一并回滚。本组件用 {@code REQUIRES_NEW} 挂起当前事务、以独立事务写入
 * {@code result=0} 的审计行，保证失败留痕不丢失，同时不改变业务失败语义。
 *
 * <p>审计 detail 由调用方按白名单构造（只含标识、动作、错误类别），不得包含签署人姓名、
 * 拒绝/撤销原因原文、问题项原文或模板正文；写入失败只记录服务端日志，不覆盖原始业务异常。
 */
@Component
public class ClinicalFailureAuditor {

    private static final Logger LOG = LoggerFactory.getLogger(ClinicalFailureAuditor.class);

    private final AuditWriter auditWriter;

    public ClinicalFailureAuditor(AuditWriter auditWriter) {
        this.auditWriter = auditWriter;
    }

    /**
     * 在独立事务中写一条失败审计（result=0）。
     *
     * @param detail 已按白名单构造的明细；本方法会防御性复制，调用方后续修改不影响审计
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
            // 审计失败不得覆盖原始业务异常（原始 409/422 仍返回给调用方）。
            LOG.error("失败路径审计落库失败：action={} resourceType={} resourceId={}",
                    action, resourceType, resourceId, auditFailure);
        }
    }
}
