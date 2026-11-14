package com.anesop.m4.impl.material;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.anesop.contracts.material.model.SpdAdapterStatus;
import com.anesop.contracts.material.model.SpdSyncResult;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m4.api.material.SpdAdapterService;
import com.anesop.m4.impl.persistence.entity.SpdSyncLogEntity;
import com.anesop.m4.impl.persistence.mapper.SpdSyncLogMapper;
import com.anesop.m4.impl.support.MaterialAccessGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SPD 供应链适配器（EXT-03，SIMULATED）实现。
 *
 * <p>每次 {@link #sync} 触发一次 **无外部 I/O** 的模拟同步并落一条 {@code spd_sync_log}：
 * <ul>
 *   <li>{@code anesop.m4.spd.simulated-available=true}（默认）：同步成功；</li>
 *   <li>{@code false}：模拟外部系统不可用，{@code success=false, degraded=true}，本地耗材
 *       库存/出入库继续可用（不阻塞临床记录）；</li>
 * </ul>
 * {@link #status} 只读最近一次日志，不产生新日志。审计 SPD_SYNC 只含
 * adapterCode/mode/success/degraded，不含外部凭据与供应商明细。
 */
@Service
public class SpdAdapterServiceImpl implements SpdAdapterService {

    static final String ADAPTER_CODE = "EXT-03";
    private static final String MODE_SIMULATED = "SIMULATED";
    private static final String OPERATION_SYNC = "SYNC";
    private static final String RESOURCE_TYPE = "SPD_ADAPTER";
    private static final String MESSAGE_OK = "未接入真实 SPD 系统（EXT-03），当前为模拟同步";
    private static final String MESSAGE_DEGRADED =
            "SPD 适配器不可用（EXT-03 模拟失败），已降级为本地库存可用";
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);

    private final SpdSyncLogMapper logMapper;
    private final MaterialAccessGuard accessGuard;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean simulatedAvailable;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/审计由容器管理，服务不修改其内部状态")
    public SpdAdapterServiceImpl(SpdSyncLogMapper logMapper, MaterialAccessGuard accessGuard,
            AuditWriter auditWriter, ObjectMapper objectMapper, Clock clock,
            @Value("${anesop.m4.spd.simulated-available:true}") boolean simulatedAvailable) {
        this.logMapper = logMapper;
        this.accessGuard = accessGuard;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.simulatedAvailable = simulatedAvailable;
    }

    @Override
    public SpdAdapterStatus status(AuthPrincipal caller, AuthRequestContext context) {
        // 契约承诺 SPD 状态与库存同为科室数据范围：SELF/ROOM/无范围 fail-closed 403。
        DataScopeAssignments scope = accessGuard.scope(caller);
        accessGuard.visibleDepartmentIds(scope, caller);
        SpdSyncLogEntity last = logMapper.selectLast();
        boolean available = last == null || Boolean.TRUE.equals(last.getSuccess());
        long pending = logMapper.countPending();
        SpdAdapterStatus status = new SpdAdapterStatus();
        status.setAdapterCode(ADAPTER_CODE);
        status.setName("SPD 供应链适配器（模拟）");
        status.setMode(SpdAdapterStatus.ModeEnum.SIMULATED);
        status.setAvailable(available);
        status.setDegraded(!available);
        status.setLastSyncedAt(last == null ? null : last.getOccurredAt().atOffset(DISPLAY_OFFSET));
        status.setPendingSyncCount(Math.toIntExact(pending));
        status.setMessage(available ? MESSAGE_OK : MESSAGE_DEGRADED);
        return status;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SpdSyncResult sync(AuthPrincipal caller, AuthRequestContext context) {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
        boolean success = simulatedAvailable;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("mode", MODE_SIMULATED);
        detail.put("success", success);
        detail.put("reason", success ? "SIMULATED_OK" : "SIMULATED_UNAVAILABLE");

        SpdSyncLogEntity log = new SpdSyncLogEntity();
        log.setId(UUID.randomUUID());
        log.setAdapterCode(ADAPTER_CODE);
        log.setOperation(OPERATION_SYNC);
        log.setSuccess(success);
        log.setDegraded(!success);
        log.setDetail(toJson(detail));
        log.setOccurredAt(now);
        logMapper.insertLog(log);

        Map<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("adapterCode", ADAPTER_CODE);
        auditDetail.put("mode", MODE_SIMULATED);
        auditDetail.put("success", success);
        auditDetail.put("degraded", !success);
        auditWriter.record("SPD_SYNC", RESOURCE_TYPE, ADAPTER_CODE, success, auditDetail,
                caller == null ? null : caller.userId(),
                caller == null ? null : caller.username(), context);

        SpdSyncResult result = new SpdSyncResult();
        result.setAdapterCode(ADAPTER_CODE);
        result.setSuccess(success);
        result.setDegraded(!success);
        result.setSyncedAt(now.atOffset(DISPLAY_OFFSET));
        result.setPulledCount(0);
        result.setPushedCount(0);
        result.setMessage(success ? MESSAGE_OK : MESSAGE_DEGRADED);
        return result;
    }

    private String toJson(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("序列化 SPD 同步状态摘要失败", failure);
        }
    }
}
