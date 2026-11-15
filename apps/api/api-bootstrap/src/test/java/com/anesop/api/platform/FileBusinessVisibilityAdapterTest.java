package com.anesop.api.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.anesop.contracts.scheduling.model.Schedule;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m1.api.schedule.SchedulingService;
import com.anesop.m2.api.record.AnesthesiaRecordService;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * 文件业务可见性适配器测试（M8-M0-04 评审 B-1）：m1/m2 数据范围查询失败/不可见/异常
 * 必须 fail-closed；REPORT 无对象标识必须 fail-closed（Q-24）。
 */
class FileBusinessVisibilityAdapterTest {

    private static final AuthPrincipal CALLER = new AuthPrincipal(
            UUID.fromString("e0000000-0000-4000-8000-000000000001"), "synth.visibility",
            "jti-visibility", Instant.parse("2026-11-15T12:00:00Z"), "sid-visibility");
    private static final String SCHEDULE_ID = "80000000-0000-4000-8000-000000000001";
    private static final String RECORD_ID = "90000000-0000-4000-8000-000000000001";

    private final SchedulingService schedulingService = mock(SchedulingService.class);
    private final AnesthesiaRecordService recordService = mock(AnesthesiaRecordService.class);
    private final FileBusinessVisibilityAdapter adapter =
            new FileBusinessVisibilityAdapter(schedulingService, recordService);

    @Test
    void scheduleAndRecordVisibilityFollowBusinessServiceDataScope() {
        when(schedulingService.get(SCHEDULE_ID, CALLER)).thenReturn(mock(Schedule.class));
        when(recordService.get(RECORD_ID, CALLER)).thenReturn(mock(
                com.anesop.contracts.anesthesia.model.AnesthesiaRecord.class));

        assertThat(adapter.isVisible(CALLER, "SCHEDULE", SCHEDULE_ID)).isTrue();
        assertThat(adapter.isVisible(CALLER, "RECORD", RECORD_ID)).isTrue();
    }

    @Test
    void outOfScopeAndFailuresAreClosed() {
        when(schedulingService.get(SCHEDULE_ID, CALLER))
                .thenThrow(new NoSuchElementException("术间不存在或不可见"));
        when(recordService.get(RECORD_ID, CALLER))
                .thenThrow(new AccessDeniedException("越权"));

        assertThat(adapter.isVisible(CALLER, "SCHEDULE", SCHEDULE_ID)).isFalse();
        assertThat(adapter.isVisible(CALLER, "RECORD", RECORD_ID)).isFalse();
        assertThat(adapter.isVisible(CALLER, "REPORT", "REPORT-2026-001"))
                .as("REPORT 无对象标识 → fail-closed")
                .isFalse();
        assertThat(adapter.isVisible(CALLER, "TEMPLATE", "anything")).isFalse();
        assertThat(adapter.isVisible(CALLER, "SCHEDULE", " ")).isFalse();
        assertThat(adapter.isVisible(null, "SCHEDULE", SCHEDULE_ID)).isFalse();
    }

    @Test
    void unexpectedDependencyFailureIsClosed() {
        when(schedulingService.get(SCHEDULE_ID, CALLER))
                .thenThrow(new IllegalStateException("db down"));

        assertThat(adapter.isVisible(CALLER, "SCHEDULE", SCHEDULE_ID)).isFalse();
    }
}
