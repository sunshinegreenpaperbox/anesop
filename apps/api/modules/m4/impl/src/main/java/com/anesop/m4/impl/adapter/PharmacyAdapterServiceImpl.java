package com.anesop.m4.impl.adapter;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import com.anesop.contracts.drug.model.PharmacyAdapterStatus;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.api.adapter.PharmacyAdapterService;
import com.anesop.m4.impl.persistence.entity.PharmacyAdapterLogEntity;
import com.anesop.m4.impl.persistence.mapper.PharmacyAdapterLogMapper;
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

/**
 * 药房适配器（EXT-02，SIMULATED）实现。
 *
 * <p>每次 {@link #status} 执行一次 **无外部 I/O** 的模拟同步探测：
 * <ul>
 *   <li>{@code anesop.m4.pharmacy.simulated-available=true}（默认）：同步成功，
 *       {@code available=true, degraded=false}；</li>
 *   <li>{@code false}：模拟外部系统不可用，{@code available=false, degraded=true}，
 *       本地药品字典继续可用（不阻塞临床记录），并把失败写入 {@code pharmacy_adapter_log}；</li>
 * </ul>
 * 探测结果每次落一条 adapter log，并写审计 ADAPTER_SYNC（detail 只含 adapterCode/mode/
 * available/degraded，不含外部凭据与处方明细）。
 */
@Service
public class PharmacyAdapterServiceImpl implements PharmacyAdapterService {

    static final String ADAPTER_CODE = "EXT-02";
    private static final String MODE_SIMULATED = "SIMULATED";
    private static final String OPERATION_SYNC = "SYNC";
    private static final String MESSAGE_OK = "未接入真实药房系统（EXT-02），当前为模拟同步";
    private static final String MESSAGE_DEGRADED = "药房适配器不可用（EXT-02 模拟失败），已降级为本地字典可用";

    private final PharmacyAdapterLogMapper logMapper;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final boolean simulatedAvailable;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/审计由容器管理，服务不修改其内部状态")
    public PharmacyAdapterServiceImpl(PharmacyAdapterLogMapper logMapper, AuditWriter auditWriter,
            ObjectMapper objectMapper, Clock clock,
            @Value("${anesop.m4.pharmacy.simulated-available:true}") boolean simulatedAvailable) {
        this.logMapper = logMapper;
        this.auditWriter = auditWriter;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.simulatedAvailable = simulatedAvailable;
    }

    @Override
    public PharmacyAdapterStatus status(AuthPrincipal caller, AuthRequestContext context) {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
        boolean success = simulatedAvailable;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("mode", MODE_SIMULATED);
        detail.put("available", success);
        detail.put("reason", success ? "SIMULATED_OK" : "SIMULATED_UNAVAILABLE");

        PharmacyAdapterLogEntity log = new PharmacyAdapterLogEntity();
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
        auditDetail.put("available", success);
        auditDetail.put("degraded", !success);
        auditWriter.record("ADAPTER_SYNC", "PHARMACY_ADAPTER", ADAPTER_CODE, success, auditDetail,
                caller == null ? null : caller.userId(), caller == null ? null : caller.username(),
                context);

        PharmacyAdapterStatus status = new PharmacyAdapterStatus();
        status.setAdapterCode(ADAPTER_CODE);
        status.setName("药房系统适配器（模拟）");
        status.setMode(PharmacyAdapterStatus.ModeEnum.SIMULATED);
        status.setAvailable(success);
        status.setDegraded(!success);
        status.setLastSyncedAt(success ? now.atOffset(ZoneOffset.ofHours(8)) : null);
        status.setPendingSyncCount(success ? 0 : 1);
        status.setMessage(success ? MESSAGE_OK : MESSAGE_DEGRADED);
        return status;
    }

    private String toJson(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("序列化适配器状态摘要失败", failure);
        }
    }
}
