package com.anesop.m4.impl.material;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.material.model.SpdAdapterStatus;
import com.anesop.contracts.material.model.SpdSyncResult;
import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.api.security.DataScope;
import com.anesop.m0.api.security.DataScopeAssignment;
import com.anesop.m0.api.security.DataScopeAssignments;
import com.anesop.m4.impl.persistence.entity.SpdSyncLogEntity;
import com.anesop.m4.impl.persistence.mapper.SpdSyncLogMapper;
import com.anesop.m4.impl.support.MaterialAccessGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * SPD 适配器（EXT-03，SIMULATED）单测：成功/失败降级、状态只读、审计。
 */
@ExtendWith(MockitoExtension.class)
class SpdAdapterServiceImplTest {

    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-14T01:00:00Z");
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_manager", "jti-1", NOW.plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-2004");

    @Mock
    private SpdSyncLogMapper logMapper;
    @Mock
    private MaterialAccessGuard accessGuard;
    @Mock
    private AuditWriter auditWriter;

    private static final DataScopeAssignments ALL =
            DataScopeAssignments.of(List.of(new DataScopeAssignment(DataScope.ALL, List.of())));

    @Test
    void statusWithoutLogsIsAvailableAndReadOnly() {
        when(logMapper.selectLast()).thenReturn(null);
        when(logMapper.countPending()).thenReturn(0L);
        SpdAdapterServiceImpl service = service(true);

        SpdAdapterStatus status = service.status(CALLER, CONTEXT);

        assertThat(status.getAdapterCode()).isEqualTo("EXT-03");
        assertThat(status.getMode()).isEqualTo(SpdAdapterStatus.ModeEnum.SIMULATED);
        assertThat(status.getAvailable()).isTrue();
        assertThat(status.getDegraded()).isFalse();
        assertThat(status.getLastSyncedAt()).isNull();
        assertThat(status.getPendingSyncCount()).isZero();
        verify(logMapper, org.mockito.Mockito.never()).insertLog(any());
        verify(auditWriter, org.mockito.Mockito.never()).record(anyString(), anyString(),
                anyString(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void statusFailsClosedWhenScopeHasNoDepartmentDimension() {
        when(accessGuard.scope(CALLER)).thenThrow(new AccessDeniedException("当前用户无数据范围"));

        assertThatThrownBy(() -> service(true).status(CALLER, CONTEXT))
                .isInstanceOf(AccessDeniedException.class);
        verify(logMapper, never()).selectLast();
    }

    @Test
    void statusAfterFailureIsDegraded() {
        SpdSyncLogEntity last = new SpdSyncLogEntity();
        last.setSuccess(false);
        last.setDegraded(true);
        last.setOccurredAt(NOW);
        when(logMapper.selectLast()).thenReturn(last);
        when(logMapper.countPending()).thenReturn(2L);

        SpdAdapterStatus status = service(true).status(CALLER, CONTEXT);

        assertThat(status.getAvailable()).isFalse();
        assertThat(status.getDegraded()).isTrue();
        assertThat(status.getPendingSyncCount()).isEqualTo(2);
        assertThat(status.getLastSyncedAt()).isNotNull();
    }

    @Test
    void syncSuccessLogsAndAudits() {
        when(logMapper.insertLog(any())).thenReturn(1);
        SpdSyncResult result = service(true).sync(CALLER, CONTEXT);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getDegraded()).isFalse();
        assertThat(result.getSyncedAt()).isNotNull();
        verify(logMapper).insertLog(any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> detail = ArgumentCaptor.forClass(Map.class);
        verify(auditWriter).record(eq("SPD_SYNC"), eq("SPD_ADAPTER"), eq("EXT-03"), eq(true),
                detail.capture(), eq(ACTOR), eq("synth_manager"), eq(CONTEXT));
        assertThat(detail.getValue()).containsEntry("adapterCode", "EXT-03")
                .containsEntry("success", true);
        assertThat(String.valueOf(detail.getValue())).doesNotContain("凭据");
    }

    @Test
    void syncFailureDegradesWithoutBlockingLocalStock() {
        when(logMapper.insertLog(any())).thenReturn(1);
        SpdSyncResult result = service(false).sync(CALLER, CONTEXT);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getDegraded()).isTrue();
        assertThat(result.getMessage()).contains("降级");
        verify(auditWriter).record(eq("SPD_SYNC"), eq("SPD_ADAPTER"), eq("EXT-03"), eq(false),
                any(), eq(ACTOR), eq("synth_manager"), eq(CONTEXT));
    }

    private SpdAdapterServiceImpl service(boolean available) {
        lenient().when(accessGuard.scope(CALLER)).thenReturn(ALL);
        lenient().when(accessGuard.visibleDepartmentIds(ALL, CALLER)).thenReturn(null);
        return new SpdAdapterServiceImpl(logMapper, accessGuard, auditWriter, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC), available);
    }
}
