package com.anesop.m1.impl.patient;

import com.anesop.contracts.patient.model.PageMeta;
import com.anesop.contracts.patient.model.Patient;
import com.anesop.contracts.patient.model.PatientPage;
import com.anesop.contracts.patient.model.PatientSummary;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m0.api.security.DataScopeResolver;
import com.anesop.m1.api.patient.PatientQuery;
import com.anesop.m1.api.patient.PatientQueryService;
import com.anesop.m1.impl.persistence.entity.PatientSnapshotEntity;
import com.anesop.m1.impl.persistence.mapper.PatientSnapshotMapper;
import com.anesop.m1.impl.support.InpatientNoHasher;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * 患者摘要查询实现（M2-M1-01，契约 patient.yaml 0.3.0）。
 *
 * <p>只返回排程/申请所需的最小必要字段：姓名、性别、出生日期/年龄、脱敏住院号、
 * 科室/病区/床号；**不返回证件号与手机号**（含脱敏值）。数据范围与申请列表一致：
 * 无范围 403、单对象越界 404；SELF 按快照创建人过滤（M1 暂无患者—医师归属关系）。
 */
@Service
public class PatientQueryServiceImpl implements PatientQueryService {

    private static final int MAX_PAGE_SIZE = 200;

    private final PatientSnapshotMapper patientSnapshotMapper;
    private final DataScopeResolver dataScopeResolver;
    private final InpatientNoHasher inpatientNoHasher;

    public PatientQueryServiceImpl(PatientSnapshotMapper patientSnapshotMapper,
            DataScopeResolver dataScopeResolver,
            InpatientNoHasher inpatientNoHasher) {
        this.patientSnapshotMapper = patientSnapshotMapper;
        this.dataScopeResolver = dataScopeResolver;
        this.inpatientNoHasher = inpatientNoHasher;
    }

    @Override
    public PatientPage list(PatientQuery query, AuthPrincipal caller) {
        int page = Math.max(1, query.page());
        int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
        DataScopeAssignments scope = dataScopeResolver.resolve(caller == null ? null : caller.userId());
        requireScope(scope);

        QueryWrapper<PatientSnapshotEntity> wrapper = new QueryWrapper<>();
        if (query.hisPatientNo() != null && !query.hisPatientNo().isBlank()) {
            wrapper.eq("his_patient_no", query.hisPatientNo().trim());
        }
        if (query.patientNo() != null && !query.patientNo().isBlank()) {
            // M1 快照以 his_patient_no 作为院内患者号（同一来源），两者等价。
            wrapper.eq("his_patient_no", query.patientNo().trim());
        }
        if (query.name() != null && !query.name().isBlank()) {
            wrapper.like("name", query.name().trim());
        }
        if (query.inpatientNo() != null && !query.inpatientNo().isBlank()) {
            // 只按哈希等值匹配：住院号明文不参与查询、不落日志。
            wrapper.eq("inpatient_no_hash", inpatientNoHasher.hash(query.inpatientNo()));
        }
        if (query.departmentId() != null && !query.departmentId().isBlank()) {
            UUID deptId = parseUuid(query.departmentId());
            if (deptId == null) {
                wrapper.apply("1 = 0");
            } else {
                wrapper.eq("dept_id", deptId);
            }
        }
        applyScope(wrapper, scope, caller);

        Long counted = patientSnapshotMapper.selectCount(wrapper);
        long total = counted == null ? 0 : counted;
        long offset = (long) (page - 1) * size;
        List<PatientSummary> items = new ArrayList<>();
        if (total > offset) {
            List<PatientSnapshotEntity> rows = patientSnapshotMapper.selectList(wrapper
                    .orderByDesc("updated_at")
                    .orderByAsc("id")
                    .last("LIMIT " + size + " OFFSET " + offset));
            for (PatientSnapshotEntity row : rows) {
                items.add(toSummary(row));
            }
        }
        PageMeta meta = new PageMeta();
        meta.setPage(page);
        meta.setSize(size);
        meta.setTotal((int) Math.min(Integer.MAX_VALUE, total));
        meta.setTotalPages(total == 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size));
        PatientPage result = new PatientPage();
        result.setItems(items);
        result.setMeta(meta);
        return result;
    }

    @Override
    public Patient get(String patientId, AuthPrincipal caller) {
        DataScopeAssignments scope = dataScopeResolver.resolve(caller == null ? null : caller.userId());
        requireScope(scope);
        UUID id = parseUuid(patientId);
        if (id == null) {
            throw new NoSuchElementException("患者不存在或不可见");
        }
        PatientSnapshotEntity patient = patientSnapshotMapper.selectById(id);
        if (patient == null) {
            throw new NoSuchElementException("患者不存在或不可见");
        }
        if (scope.isSelfOnly()
                && !java.util.Objects.equals(patient.getCreatedBy(), caller.userId())) {
            throw new NoSuchElementException("患者不存在或不可见");
        }
        if (!scope.isSelfOnly() && scope.requiresObjectFilter()
                && !inScope(scope, patient.getDeptId(), patient.getSiteId())) {
            throw new NoSuchElementException("患者不存在或不可见");
        }
        return toPatient(patient);
    }

    private static void requireScope(DataScopeAssignments scope) {
        if (scope == null || scope.isDenied()) {
            throw new AccessDeniedException("当前用户无数据范围");
        }
    }

    private static void applyScope(QueryWrapper<PatientSnapshotEntity> wrapper,
            DataScopeAssignments scope, AuthPrincipal caller) {
        if (!scope.requiresObjectFilter()) {
            return;
        }
        if (scope.isSelfOnly()) {
            wrapper.eq("created_by", caller.userId());
            return;
        }
        List<UUID> orgIds = scope.allowedOrgIds().stream()
                .map(PatientQueryServiceImpl::parseUuid)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (orgIds.isEmpty()) {
            throw new AccessDeniedException("当前用户无可用组织数据范围");
        }
        wrapper.and(condition -> condition
                .in("dept_id", orgIds)
                .or()
                .in("site_id", orgIds));
    }

    private static boolean inScope(DataScopeAssignments scope, UUID deptId, UUID siteId) {
        if (!scope.requiresObjectFilter() || scope.isUnrestricted() || scope.isSelfOnly()) {
            return true;
        }
        Set<UUID> ids = scope.allowedOrgIds().stream()
                .map(PatientQueryServiceImpl::parseUuid)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        return (deptId != null && ids.contains(deptId)) || (siteId != null && ids.contains(siteId));
    }

    private static PatientSummary toSummary(PatientSnapshotEntity patient) {
        PatientSummary dto = new PatientSummary();
        dto.setPatientId(patient.getId().toString());
        dto.setPatientNo(patient.getHisPatientNo());
        dto.setHisPatientNo(patient.getHisPatientNo());
        dto.setName(patient.getName());
        dto.setGender(PatientSummary.GenderEnum.fromValue(patient.getGender()));
        dto.setAge(patient.getAge());
        // B-1 兼容字段：保留字段定义但不返回真实值（空字符串），避免调用方解析失败。
        dto.setIdCardMasked("");
        dto.setPhoneMasked("");
        dto.setInpatientNoMasked(patient.getInpatientNoMasked());
        dto.setDepartmentId(patient.getDeptId() == null ? null : patient.getDeptId().toString());
        dto.setDepartmentName(patient.getDeptName());
        dto.setWard(patient.getWard());
        dto.setBedNo(patient.getBedNo());
        dto.setSiteId(patient.getSiteId() == null ? null : patient.getSiteId().toString());
        dto.setCreatedAt(offset(patient.getCreatedAt()));
        dto.setCreatedBy(patient.getCreatedBy() == null ? null : patient.getCreatedBy().toString());
        dto.setUpdatedAt(offset(patient.getUpdatedAt()));
        dto.setUpdatedBy(patient.getUpdatedBy() == null ? null : patient.getUpdatedBy().toString());
        dto.setVersion(patient.getVersion() == null ? 0 : patient.getVersion().intValue());
        return dto;
    }

    private static Patient toPatient(PatientSnapshotEntity patient) {
        Patient dto = new Patient();
        dto.setPatientId(patient.getId().toString());
        dto.setPatientNo(patient.getHisPatientNo());
        dto.setHisPatientNo(patient.getHisPatientNo());
        dto.setName(patient.getName());
        dto.setGender(Patient.GenderEnum.fromValue(patient.getGender()));
        dto.setBirthDate(patient.getBirthDate());
        dto.setAge(patient.getAge());
        // B-1 兼容字段：保留字段定义但不返回真实值（空字符串），避免调用方解析失败。
        dto.setIdCardMasked("");
        dto.setPhoneMasked("");
        dto.setInpatientNo("");
        dto.setInpatientNoMasked(patient.getInpatientNoMasked());
        dto.setDepartmentId(patient.getDeptId() == null ? null : patient.getDeptId().toString());
        dto.setDepartmentName(patient.getDeptName());
        dto.setWard(patient.getWard());
        dto.setBedNo(patient.getBedNo());
        dto.setSiteId(patient.getSiteId() == null ? null : patient.getSiteId().toString());
        dto.setCreatedAt(offset(patient.getCreatedAt()));
        dto.setCreatedBy(patient.getCreatedBy() == null ? null : patient.getCreatedBy().toString());
        dto.setUpdatedAt(offset(patient.getUpdatedAt()));
        dto.setUpdatedBy(patient.getUpdatedBy() == null ? null : patient.getUpdatedBy().toString());
        dto.setVersion(patient.getVersion() == null ? 0 : patient.getVersion().intValue());
        return dto;
    }

    private static java.time.OffsetDateTime offset(java.time.Instant instant) {
        return instant == null ? null : instant.atOffset(java.time.ZoneOffset.ofHours(8));
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
