package com.anesop.m4.impl.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.drug.model.PharmacyAdapterStatus;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m4.impl.persistence.entity.PharmacyAdapterLogEntity;
import com.anesop.m4.impl.persistence.mapper.PharmacyAdapterLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 药房适配器（EXT-02 SIMULATED）单测：成功同步与失败降级都必须落 adapter log 并写审计。
 */
@ExtendWith(MockitoExtension.class)
class PharmacyAdapterServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-11-14T01:00:00Z");
    private static final AuthPrincipal CALLER = new AuthPrincipal(
            UUID.fromString("10000000-0000-4000-8000-000000000001"), "synth_admin", "jti-1",
            NOW.plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-3001");

    @Mock
    private PharmacyAdapterLogMapper logMapper;
    @Mock
    private AuditWriter auditWriter;

    @Test
    void availableAdapterReportsSyncedAndLogs() {
        when(logMapper.insertLog(any())).thenReturn(1);
        PharmacyAdapterServiceImpl service = new PharmacyAdapterServiceImpl(logMapper, auditWriter,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), true);

        PharmacyAdapterStatus status = service.status(CALLER, CONTEXT);

        assertThat(status.getMode()).isEqualTo(PharmacyAdapterStatus.ModeEnum.SIMULATED);
        assertThat(status.getAvailable()).isTrue();
        assertThat(status.getDegraded()).isFalse();
        assertThat(status.getPendingSyncCount()).isZero();
        assertThat(status.getLastSyncedAt()).isNotNull();

        ArgumentCaptor<PharmacyAdapterLogEntity> log = ArgumentCaptor.forClass(
                PharmacyAdapterLogEntity.class);
        verify(logMapper).insertLog(log.capture());
        assertThat(log.getValue().getAdapterCode()).isEqualTo("EXT-02");
        assertThat(log.getValue().getSuccess()).isTrue();
    }

    @Test
    void unavailableAdapterDegradesAndKeepsLocalDictionary() {
        when(logMapper.insertLog(any())).thenReturn(1);
        PharmacyAdapterServiceImpl service = new PharmacyAdapterServiceImpl(logMapper, auditWriter,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), false);

        PharmacyAdapterStatus status = service.status(CALLER, CONTEXT);

        assertThat(status.getAvailable()).isFalse();
        assertThat(status.getDegraded()).isTrue();
        assertThat(status.getLastSyncedAt()).isNull();
        assertThat(status.getPendingSyncCount()).isEqualTo(1);
        assertThat(status.getMessage()).contains("降级");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("ADAPTER_SYNC"), eq("PHARMACY_ADAPTER"), eq("EXT-02"),
                eq(false), detail.capture(), eq(CALLER.userId()), eq("synth_admin"), eq(CONTEXT));
        assertThat(detail.getValue()).containsEntry("degraded", true)
                .containsEntry("available", false);
    }
}
