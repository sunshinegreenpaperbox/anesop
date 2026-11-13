package com.anesop.m2.impl.consent;

import com.anesop.contracts.anesthesia.model.ConsentRecord;
import com.anesop.contracts.anesthesia.model.ConsentRecordList;
import com.anesop.contracts.anesthesia.model.ConsentTemplate;
import com.anesop.contracts.anesthesia.model.ConsentTemplateList;
import com.anesop.contracts.anesthesia.model.CreateConsentRequest;
import com.anesop.contracts.anesthesia.model.DeclineConsentRequest;
import com.anesop.contracts.anesthesia.model.RevokeConsentRequest;
import com.anesop.contracts.anesthesia.model.SignConsentRequest;
import com.anesop.contracts.scheduling.model.SurgeryApplication;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m1.api.application.SurgeryApplicationService;
import com.anesop.m2.api.consent.ConsentService;
import com.anesop.m2.api.error.M2ConflictException;
import com.anesop.m2.api.error.M2UnprocessableException;
import com.anesop.m2.api.error.M2ValidationException;
import com.anesop.m2.impl.persistence.entity.ConsentRecordEntity;
import com.anesop.m2.impl.persistence.entity.ConsentTemplateEntity;
import com.anesop.m2.impl.persistence.mapper.ConsentRecordMapper;
import com.anesop.m2.impl.persistence.mapper.ConsentTemplateMapper;
import com.anesop.m2.impl.support.AnesthesiaApplicationSummaries;
import com.anesop.m2.impl.support.ClinicalFailureAuditor;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知情同意服务实现（M2-M5-01，契约 anesthesia.yaml 0.4.0）。
 *
 * <p>关键约束：
 * <ul>
 *   <li>模板版本不可变：签署记录冻结 templateId + templateVersion；模板内容参与签名摘要，
 *       模板内容变化只可能来自新版本（数据库 append-only 触发器保证）。</li>
 *   <li>状态机：WAITING_SIGN → SIGNED | DECLINED；SIGNED → REVOKED。全部状态变更使用
 *       {@code WHERE id=? AND version=? AND status=?} 条件更新，返回 0 行即 409。</li>
 *   <li>CA 占位（EXT-05）：签名只生成 signatureRef（SIM-CA-&lt;uuid&gt;）与
 *       SHA-256 signatureHash（同意元数据 + 模板正文摘要，输入不含姓名），
 *       caStatus=SIMULATED；不保存签名原文/图片/证书链。</li>
 *   <li>数据范围复用 M1 申请可见性（只调 m1-api）：按申请列表 403（集合语义）、
 *       单记录查询/变更 404、创建时申请不可见 422。</li>
 *   <li>审计 CONSENT_CREATE/SIGN/DECLINE/REVOKE 与业务同事务；detail 不含签署人姓名、
 *       拒绝/撤销原因原文、签名摘要或模板正文。</li>
 * </ul>
 */
@Service
public class ConsentServiceImpl implements ConsentService {

    private static final String STATUS_WAITING_SIGN = "WAITING_SIGN";
    private static final String STATUS_SIGNED = "SIGNED";
    private static final String STATUS_DECLINED = "DECLINED";
    private static final String STATUS_REVOKED = "REVOKED";
    private static final String TEMPLATE_ACTIVE = "ACTIVE";
    private static final String TEMPLATE_RETIRED = "RETIRED";
    private static final String CA_SIMULATED = "SIMULATED";
    private static final String CA_PENDING = "PENDING";
    private static final String RESOURCE_TYPE = "CONSENT_RECORD";
    private static final String TEMPLATE_RESOURCE_TYPE = "CONSENT_TEMPLATE";
    private static final String ACTION_CREATE = "CONSENT_CREATE";
    private static final String ACTION_SIGN = "CONSENT_SIGN";
    private static final String ACTION_DECLINE = "CONSENT_DECLINE";
    private static final String ACTION_REVOKE = "CONSENT_REVOKE";
    private static final String ACTION_TEMPLATE_RETIRE = "CONSENT_TEMPLATE_RETIRE";
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);
    private static final int MAX_SIGNER_NAME = 64;
    private static final int MAX_REASON = 500;
    private static final String SIGNATURE_REF_PREFIX = "SIM-CA-";

    /** 允许签署/拒绝/撤销的申请状态；取消/驳回为终态。 */
    private static final Set<String> ACTIVE_APPLICATION_STATUSES =
            Set.of("SUBMITTED", "ACCEPTED", "SCHEDULED");

    private final ConsentRecordMapper recordMapper;
    private final ConsentTemplateMapper templateMapper;
    private final SurgeryApplicationService applicationService;
    private final AuditWriter auditWriter;
    private final ClinicalFailureAuditor failureAuditor;
    private final Clock clock;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例 Mapper/服务由容器管理，服务不修改其内部状态")
    public ConsentServiceImpl(ConsentRecordMapper recordMapper,
            ConsentTemplateMapper templateMapper,
            SurgeryApplicationService applicationService,
            AuditWriter auditWriter,
            ClinicalFailureAuditor failureAuditor,
            Clock clock) {
        this.recordMapper = recordMapper;
        this.templateMapper = templateMapper;
        this.applicationService = applicationService;
        this.auditWriter = auditWriter;
        this.failureAuditor = failureAuditor;
        this.clock = clock;
    }

    @Override
    public ConsentTemplateList listTemplates(String templateCode, Boolean effectiveOnly,
            Boolean includeRetired, AuthPrincipal caller) {
        QueryWrapper<ConsentTemplateEntity> query = new QueryWrapper<>();
        if (templateCode != null && !templateCode.isBlank()) {
            query.eq("template_code", templateCode.trim());
        }
        boolean effective = effectiveOnly == null || effectiveOnly;
        if (effective) {
            query.le("effective_at", clock.instant());
        }
        // 默认只返回 ACTIVE；历史追溯显式 includeRetired=true。
        if (includeRetired == null || !includeRetired) {
            query.eq("status", TEMPLATE_ACTIVE);
        }
        query.orderByAsc("template_code").orderByDesc("version");
        List<ConsentTemplateEntity> rows = templateMapper.selectList(query);
        ConsentTemplateList result = new ConsentTemplateList();
        List<ConsentTemplate> items = new ArrayList<>();
        for (ConsentTemplateEntity row : rows) {
            items.add(toTemplateDto(row));
        }
        result.setItems(items);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConsentTemplate retireTemplate(String templateId, AuthPrincipal caller,
            AuthRequestContext context) {
        UUID id = parseUuid(templateId);
        ConsentTemplateEntity entity = id == null ? null : templateMapper.selectById(id);
        if (entity == null) {
            // 模板为全局参考数据：不存在/非法标识统一 404，不泄露格式细节。
            throw new NoSuchElementException("同意模板不存在或不可见");
        }
        if (TEMPLATE_RETIRED.equals(entity.getStatus())) {
            throw new M2UnprocessableException("templateId", "同意模板已停用，不可重复停用");
        }
        Instant retiredAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        int updated = templateMapper.retire(entity.getId(), retiredAt, actorId(caller));
        if (updated == 0) {
            throw new M2UnprocessableException("templateId", "同意模板已被修改或已停用，请刷新后重试");
        }
        entity.setStatus(TEMPLATE_RETIRED);
        entity.setRetiredAt(retiredAt);
        entity.setRetiredBy(actorId(caller));
        entity.setUpdatedAt(retiredAt);
        entity.setUpdatedBy(actorId(caller));
        auditTemplateRetire(entity, caller, context);
        return toTemplateDto(entity);
    }

    @Override
    public ConsentRecordList listByApplication(String applicationId, AuthPrincipal caller) {
        UUID id = parseUuid(applicationId);
        if (id == null) {
            throw new M2ValidationException("applicationId", "手术申请标识格式不正确");
        }
        SurgeryApplication application;
        try {
            application = applicationService.get(id.toString(), caller);
        } catch (NoSuchElementException notVisible) {
            // 集合查询越权语义：申请不可见/不存在统一 403，不返回空列表（不泄露存在性）。
            throw new AccessDeniedException("手术申请不存在或超出数据范围");
        }
        List<ConsentRecordEntity> rows = recordMapper.selectList(
                new QueryWrapper<ConsentRecordEntity>()
                        .eq("application_id", id)
                        .orderByDesc("created_at")
                        .orderByDesc("version"));
        ConsentRecordList result = new ConsentRecordList();
        result.setApplication(AnesthesiaApplicationSummaries.toSummary(application));
        List<ConsentRecord> items = new ArrayList<>();
        for (ConsentRecordEntity row : rows) {
            items.add(toDto(row));
        }
        result.setItems(items);
        return result;
    }

    @Override
    public ConsentRecord get(String consentId, AuthPrincipal caller) {
        ConsentRecordEntity entity = requireEntity(consentId);
        // 复用 M1 申请可见性：申请不存在或越界 → NoSuchElementException → 404。
        applicationService.get(entity.getApplicationId().toString(), caller);
        return toDto(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConsentRecord create(CreateConsentRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        UUID applicationId = parseUuid(request == null ? null : request.getApplicationId());
        UUID templateId = parseUuid(request == null ? null : request.getTemplateId());
        if (applicationId == null) {
            throw new M2ValidationException("applicationId", "手术申请标识格式不正确");
        }
        if (templateId == null) {
            throw new M2ValidationException("templateId", "模板标识格式不正确");
        }
        int templateVersion = requireTemplateVersion(request.getTemplateVersion());
        String signerName = requireText(request.getSignerName(), "signerName", MAX_SIGNER_NAME);
        String signerRelation = requireValue(request.getSignerRelation(), "signerRelation");
        String signMethod = requireValue(request.getSignMethod(), "signMethod");

        SurgeryApplication application = loadApplicationForWrite(applicationId, caller);
        requireActiveApplication(application);
        if (application.getPatient() == null) {
            throw new M2UnprocessableException("applicationId", "手术申请缺少患者快照");
        }
        ConsentTemplateEntity template = loadTemplate(templateId, templateVersion);

        if (hasActiveRecord(applicationId, templateId, templateVersion)) {
            throw new M2ConflictException("该申请与模板版本已有生效的同意记录");
        }

        Instant now = clock.instant();
        ConsentRecordEntity entity = new ConsentRecordEntity();
        entity.setId(UUID.randomUUID());
        entity.setApplicationId(applicationId);
        entity.setPatientId(UUID.fromString(application.getPatient().getPatientId()));
        entity.setTemplateId(template.getId());
        entity.setTemplateVersion(template.getVersion());
        entity.setStatus(STATUS_WAITING_SIGN);
        entity.setSignerName(signerName);
        entity.setSignerRelation(signerRelation);
        entity.setSignMethod(signMethod);
        entity.setCaStatus(CA_PENDING);
        entity.setCreatedAt(now);
        entity.setCreatedBy(actorId(caller));
        entity.setUpdatedAt(now);
        entity.setUpdatedBy(actorId(caller));
        entity.setVersion(1L);
        try {
            recordMapper.insertRecord(entity);
        } catch (DuplicateKeyException concurrentCreate) {
            // 并发重复创建：部分唯一索引 uk_consent_record_active 兜底。
            throw new M2ConflictException("该申请与模板版本已有生效的同意记录");
        }
        audit(ACTION_CREATE, entity, caller, context);
        return toDto(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConsentRecord sign(String consentId, SignConsentRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        try {
            ConsentRecordEntity entity = requireEntity(consentId);
            // S-7：申请须仍为活动状态；已取消/驳回申请不可再签署（409，与创建语义一致）。
            SurgeryApplication application = applicationService.get(
                    entity.getApplicationId().toString(), caller);
            requireActiveApplication(application);
            requireStatus(entity, STATUS_WAITING_SIGN, "签署", "同意记录已签署，不可重复签署");
            requireBody(request);
            requireVersion(entity, request.getVersion());
            // S-6：同一申请同时最多一条有效 SIGNED（撤销后 REVOKED 不占位）。
            if (hasOtherSignedRecord(entity.getApplicationId(), entity.getId())) {
                throw new M2ConflictException("该申请已有未撤销的有效签署同意，请先撤销后再签署");
            }
            ConsentTemplateEntity template = templateMapper.selectById(entity.getTemplateId());
            if (template == null || !Objects.equals(template.getVersion(), entity.getTemplateVersion())) {
                throw new M2ConflictException("同意模板版本已失效，无法签署");
            }
            if (TEMPLATE_RETIRED.equals(template.getStatus())) {
                throw new M2ConflictException("同意模板已停用，无法完成签署");
            }

            Instant signedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            String signatureRef = SIGNATURE_REF_PREFIX + entity.getId();
            String signatureHash = signatureHash(entity, template, signedAt);
            int updated;
            try {
                updated = recordMapper.sign(entity.getId(), entity.getVersion(), signedAt,
                        actorId(caller), signatureRef, signatureHash, CA_SIMULATED);
            } catch (DuplicateKeyException concurrentSign) {
                // 部分唯一索引 uk_consent_record_signed_application 兜底并发重复签署。
                throw new M2ConflictException("该申请已有未撤销的有效签署同意，请先撤销后再签署");
            }
            if (updated == 0) {
                throw new M2ConflictException("同意记录已被修改或已签署，请刷新后重试");
            }
            entity.setStatus(STATUS_SIGNED);
            entity.setSignedAt(signedAt);
            entity.setSignedBy(actorId(caller));
            entity.setSignatureRef(signatureRef);
            entity.setSignatureHash(signatureHash);
            entity.setCaStatus(CA_SIMULATED);
            entity.setUpdatedAt(signedAt);
            entity.setUpdatedBy(actorId(caller));
            entity.setVersion(entity.getVersion() + 1);
            audit(ACTION_SIGN, entity, caller, context);
            return toDto(entity);
        } catch (M2ConflictException | M2UnprocessableException failure) {
            auditFailure(ACTION_SIGN, consentId, failure, caller, context);
            throw failure;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConsentRecord decline(String consentId, DeclineConsentRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        try {
            ConsentRecordEntity entity = requireEntity(consentId);
            applicationService.get(entity.getApplicationId().toString(), caller);
            requireStatus(entity, STATUS_WAITING_SIGN, "拒绝", "同意记录已签署，不可再拒绝");
            requireBody(request);
            requireVersion(entity, request.getVersion());
            String reason = requireText(request.getReason(), "reason", MAX_REASON);

            Instant declinedAt = clock.instant();
            int updated = recordMapper.decline(entity.getId(), entity.getVersion(), reason, declinedAt,
                    actorId(caller));
            if (updated == 0) {
                throw new M2ConflictException("同意记录已被修改或状态不允许，请刷新后重试");
            }
            entity.setStatus(STATUS_DECLINED);
            entity.setDeclineReason(reason);
            entity.setDeclinedAt(declinedAt);
            entity.setUpdatedAt(declinedAt);
            entity.setUpdatedBy(actorId(caller));
            entity.setVersion(entity.getVersion() + 1);
            audit(ACTION_DECLINE, entity, caller, context);
            return toDto(entity);
        } catch (M2ConflictException | M2UnprocessableException failure) {
            auditFailure(ACTION_DECLINE, consentId, failure, caller, context);
            throw failure;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ConsentRecord revoke(String consentId, RevokeConsentRequest request, AuthPrincipal caller,
            AuthRequestContext context) {
        try {
            ConsentRecordEntity entity = requireEntity(consentId);
            applicationService.get(entity.getApplicationId().toString(), caller);
            requireStatus(entity, STATUS_SIGNED, "撤销", "仅已签署的同意记录可撤销");
            requireBody(request);
            requireVersion(entity, request.getVersion());
            String reason = requireText(request.getReason(), "reason", MAX_REASON);

            Instant revokedAt = clock.instant();
            int updated = recordMapper.revoke(entity.getId(), entity.getVersion(), reason, revokedAt,
                    actorId(caller));
            if (updated == 0) {
                throw new M2ConflictException("同意记录已被修改或状态不允许，请刷新后重试");
            }
            entity.setStatus(STATUS_REVOKED);
            entity.setRevokeReason(reason);
            entity.setRevokedAt(revokedAt);
            entity.setRevokedBy(actorId(caller));
            entity.setUpdatedAt(revokedAt);
            entity.setUpdatedBy(actorId(caller));
            entity.setVersion(entity.getVersion() + 1);
            audit(ACTION_REVOKE, entity, caller, context);
            return toDto(entity);
        } catch (M2ConflictException | M2UnprocessableException failure) {
            auditFailure(ACTION_REVOKE, consentId, failure, caller, context);
            throw failure;
        }
    }

    // ------------------------------------------------------------- helpers --

    private SurgeryApplication loadApplicationForWrite(UUID applicationId, AuthPrincipal caller) {
        try {
            return applicationService.get(applicationId.toString(), caller);
        } catch (NoSuchElementException notVisible) {
            // 创建接口无资源路径参数，按契约语义 422（不回显申请是否存在）。
            throw new M2UnprocessableException("applicationId", "手术申请不存在或不可见");
        }
    }

    private static void requireActiveApplication(SurgeryApplication application) {
        String status = application.getStatus() == null ? null : application.getStatus().getValue();
        if (status == null || !ACTIVE_APPLICATION_STATUSES.contains(status)) {
            throw new M2ConflictException("手术申请状态不允许创建/签署知情同意");
        }
    }

    private ConsentTemplateEntity loadTemplate(UUID templateId, int templateVersion) {
        ConsentTemplateEntity template = templateMapper.selectById(templateId);
        if (template == null || !Objects.equals(template.getVersion(), templateVersion)) {
            throw new M2UnprocessableException("templateId", "模板不存在或版本不匹配");
        }
        if (template.getEffectiveAt() != null && template.getEffectiveAt().isAfter(clock.instant())) {
            throw new M2UnprocessableException("templateVersion", "模板版本尚未生效");
        }
        if (!TEMPLATE_ACTIVE.equals(template.getStatus())) {
            throw new M2UnprocessableException("templateId", "模板已停用，不可用于新签署");
        }
        return template;
    }

    /** 同一申请是否已有其他未撤销的有效签署（S-6 当前有效同意唯一）。 */
    private boolean hasOtherSignedRecord(UUID applicationId, UUID excludeConsentId) {
        Long count = recordMapper.selectCount(new QueryWrapper<ConsentRecordEntity>()
                .eq("application_id", applicationId)
                .eq("status", STATUS_SIGNED)
                .ne("id", excludeConsentId));
        return count != null && count > 0;
    }

    private boolean hasActiveRecord(UUID applicationId, UUID templateId, int templateVersion) {
        Long count = recordMapper.selectCount(new QueryWrapper<ConsentRecordEntity>()
                .eq("application_id", applicationId)
                .eq("template_id", templateId)
                .eq("template_version", templateVersion)
                .in("status", List.of(STATUS_WAITING_SIGN, STATUS_SIGNED)));
        return count != null && count > 0;
    }

    private static void requireStatus(ConsentRecordEntity entity, String expected, String action,
            String signedMessage) {
        if (STATUS_SIGNED.equals(entity.getStatus()) && !STATUS_SIGNED.equals(expected)) {
            throw new M2ConflictException(signedMessage);
        }
        if (STATUS_DECLINED.equals(entity.getStatus()) || STATUS_REVOKED.equals(entity.getStatus())) {
            throw new M2ConflictException("同意记录已" + ("DECLINED".equals(entity.getStatus())
                    ? "拒绝" : "撤销") + "，不可再" + action);
        }
        if (STATUS_SIGNED.equals(expected) && !STATUS_SIGNED.equals(entity.getStatus())) {
            throw new M2ConflictException("仅已签署的同意记录可" + action);
        }
        if (!expected.equals(entity.getStatus())) {
            throw new M2ConflictException("同意记录状态不允许" + action);
        }
    }

    private static void requireBody(Object request) {
        if (request == null) {
            throw new M2ValidationException("body", "请求体不能为空");
        }
    }

    private static void requireVersion(ConsentRecordEntity entity, Integer requestVersion) {
        if (requestVersion == null || entity.getVersion() == null
                || !entity.getVersion().equals(requestVersion.longValue())) {
            throw new M2ConflictException("同意记录版本已过期，请刷新后重试");
        }
    }

    private ConsentRecordEntity requireEntity(String consentId) {
        UUID id = parseUuid(consentId);
        ConsentRecordEntity entity = id == null ? null : recordMapper.selectById(id);
        if (entity == null) {
            throw new NoSuchElementException("同意记录不存在或不可见");
        }
        return entity;
    }

    /**
     * CA 占位签名摘要（EXT-05）：SHA-256(同意标识|申请标识|模板标识|模板版本|模板正文摘要|
     * 签署时间|签署方式)。输入不含姓名/证件号等 PHI，避免小空间哈希可逆；输出十六进制 64 字符。
     */
    private static String signatureHash(ConsentRecordEntity entity, ConsentTemplateEntity template,
            Instant signedAt) {
        String canonical = String.join("|",
                entity.getId().toString(),
                entity.getApplicationId().toString(),
                entity.getTemplateId().toString(),
                String.valueOf(entity.getTemplateVersion()),
                sha256Hex(template.getContentText()),
                signedAt.toString(),
                entity.getSignMethod());
        return sha256Hex(canonical);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private ConsentTemplate toTemplateDto(ConsentTemplateEntity entity) {
        ConsentTemplate dto = new ConsentTemplate();
        dto.setTemplateId(entity.getId().toString());
        dto.setTemplateCode(entity.getTemplateCode());
        dto.setVersion(entity.getVersion());
        dto.setTitle(entity.getTitle());
        dto.setContentText(entity.getContentText());
        dto.setEffectiveAt(offset(entity.getEffectiveAt()));
        dto.setStatus(entity.getStatus() == null ? null
                : ConsentTemplate.StatusEnum.fromValue(entity.getStatus()));
        dto.setRetiredAt(offset(entity.getRetiredAt()));
        dto.setCreatedAt(offset(entity.getCreatedAt()));
        dto.setCreatedBy(string(entity.getCreatedBy()));
        return dto;
    }

    private ConsentRecord toDto(ConsentRecordEntity entity) {
        ConsentTemplateEntity template = templateMapper.selectById(entity.getTemplateId());
        ConsentRecord dto = new ConsentRecord();
        dto.setConsentId(entity.getId().toString());
        dto.setApplicationId(entity.getApplicationId().toString());
        dto.setPatientId(entity.getPatientId().toString());
        dto.setTemplateId(entity.getTemplateId().toString());
        dto.setTemplateCode(template == null ? null : template.getTemplateCode());
        dto.setTemplateVersion(entity.getTemplateVersion());
        dto.setTemplateTitle(template == null ? null : template.getTitle());
        dto.setStatus(ConsentRecord.StatusEnum.fromValue(entity.getStatus()));
        dto.setSignerName(entity.getSignerName());
        dto.setSignerRelation(entity.getSignerRelation() == null ? null
                : ConsentRecord.SignerRelationEnum.fromValue(entity.getSignerRelation()));
        dto.setSignMethod(entity.getSignMethod() == null ? null
                : ConsentRecord.SignMethodEnum.fromValue(entity.getSignMethod()));
        dto.setSignedAt(offset(entity.getSignedAt()));
        dto.setSignedBy(string(entity.getSignedBy()));
        dto.setSignatureRef(entity.getSignatureRef());
        dto.setSignatureHash(entity.getSignatureHash());
        dto.setCaStatus(ConsentRecord.CaStatusEnum.fromValue(entity.getCaStatus()));
        dto.setDeclineReason(entity.getDeclineReason());
        dto.setDeclinedAt(offset(entity.getDeclinedAt()));
        dto.setRevokedAt(offset(entity.getRevokedAt()));
        dto.setRevokedBy(string(entity.getRevokedBy()));
        dto.setRevokeReason(entity.getRevokeReason());
        dto.setCreatedAt(offset(entity.getCreatedAt()));
        dto.setCreatedBy(string(entity.getCreatedBy()));
        dto.setUpdatedAt(offset(entity.getUpdatedAt()));
        dto.setUpdatedBy(string(entity.getUpdatedBy()));
        dto.setVersion(entity.getVersion() == null ? 0 : entity.getVersion().intValue());
        return dto;
    }

    private void auditTemplateRetire(ConsentTemplateEntity entity, AuthPrincipal caller,
            AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", ACTION_TEMPLATE_RETIRE);
        detail.put("templateId", entity.getId().toString());
        detail.put("templateCode", entity.getTemplateCode());
        detail.put("templateVersion", entity.getVersion());
        detail.put("status", entity.getStatus());
        auditWriter.record(ACTION_TEMPLATE_RETIRE, TEMPLATE_RESOURCE_TYPE,
                entity.getId().toString(), true, detail, actorId(caller),
                caller == null ? null : caller.username(), context);
    }

    /**
     * 失败路径审计（S-4）：409/422 也留痕（result=0），独立事务写入；detail 只含动作、
     * 同意标识与错误类别，不含姓名、原因原文、签名摘要或模板正文。
     */
    private void auditFailure(String action, String consentId, RuntimeException failure,
            AuthPrincipal caller, AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", action);
        detail.put("consentId", consentId);
        detail.put("error", failure instanceof M2ConflictException
                ? "STATE_CONFLICT" : "BUSINESS_RULE_VIOLATION");
        failureAuditor.recordFailure(action, RESOURCE_TYPE, consentId, detail, caller, context);
    }

    private void audit(String action, ConsentRecordEntity entity, AuthPrincipal caller,
            AuthRequestContext context) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", action);
        detail.put("consentId", entity.getId().toString());
        detail.put("applicationId", entity.getApplicationId().toString());
        detail.put("templateId", entity.getTemplateId().toString());
        detail.put("templateVersion", entity.getTemplateVersion());
        detail.put("status", entity.getStatus());
        detail.put("caStatus", entity.getCaStatus());
        detail.put("version", entity.getVersion());
        if (entity.getSignatureRef() != null) {
            detail.put("signatureRef", entity.getSignatureRef());
        }
        auditWriter.record(action, RESOURCE_TYPE, entity.getId().toString(), true, detail,
                actorId(caller), caller == null ? null : caller.username(), context);
    }

    private static int requireTemplateVersion(Integer templateVersion) {
        if (templateVersion == null || templateVersion < 1) {
            throw new M2ValidationException("templateVersion", "模板版本必须为正整数");
        }
        return templateVersion;
    }

    private static String requireValue(Enum<?> value, String field) {
        if (value == null) {
            throw new M2ValidationException(field, "字段不能为空");
        }
        return value.toString();
    }

    private static String requireText(String value, String field, int maxLength) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new M2ValidationException(field, "字段不能为空");
        }
        if (trimmed.length() > maxLength) {
            throw new M2ValidationException(field, "字段长度超出限制");
        }
        return trimmed;
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(DISPLAY_OFFSET).truncatedTo(ChronoUnit.MILLIS);
    }

    private static String string(UUID value) {
        return value == null ? null : value.toString();
    }

    private static UUID actorId(AuthPrincipal caller) {
        return caller == null ? null : caller.userId();
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException invalidUuid) {
            return null;
        }
    }
}
