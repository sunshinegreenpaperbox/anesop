package com.anesop.api.adapter;

import com.anesop.contracts.anesthesia.model.AnesthesiaRecord;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m2.api.record.AnesthesiaRecordService;
import com.anesop.m4.api.material.RecordVisibilityPort;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 麻醉记录可见性适配器（M4-M14-01 依赖倒置的装配实现）。
 *
 * <p>api-bootstrap 是唯一允许同时依赖 m2-api 与 m4-api 的装配点：本类把
 * {@link AnesthesiaRecordService#get} 适配为 m4-api 的 {@link RecordVisibilityPort}，
 * 避免 m4-impl 反向依赖 m2-api 形成模块循环（ArchUnit `modulesMustBeFreeOfCycles`）。
 *
 * <p>记录不存在或越界（m2 以 {@link NoSuchElementException} 表示）统一返回 empty，
 * 由 m4 映射 422（不泄露记录存在性）；只回传 recordId/scheduleId，不含患者信息。
 */
@Component
public class M2RecordVisibilityAdapter implements RecordVisibilityPort {

    private final AnesthesiaRecordService anesthesiaRecordService;

    @SuppressFBWarnings(value = "EI_EXPOSE_REP2",
            justification = "Spring 注入的单例领域服务由容器管理，适配器不修改其内部状态")
    public M2RecordVisibilityAdapter(AnesthesiaRecordService anesthesiaRecordService) {
        this.anesthesiaRecordService = anesthesiaRecordService;
    }

    @Override
    public Optional<RecordReference> findVisibleRecord(String recordId, AuthPrincipal caller) {
        try {
            AnesthesiaRecord record = anesthesiaRecordService.get(recordId, caller);
            if (record == null || record.getRecordId() == null) {
                return Optional.empty();
            }
            return Optional.of(new RecordReference(record.getRecordId(), record.getScheduleId()));
        } catch (NoSuchElementException notFound) {
            return Optional.empty();
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }
}
