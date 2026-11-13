package com.anesop.m1.impl.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.m1.api.notification.NotificationEvent;
import com.anesop.m1.api.notification.NotificationMessage;
import com.anesop.m1.impl.persistence.mapper.NotificationOutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 模拟通知端口单测（M2-M2-02）：publish 写 outbox（payload 为 JSON、无 PHI 键）、
 * deliver 模拟投递成功；事件类型非法直接拒绝。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SimulatedNotificationPortTest {

    private static final Instant NOW = Instant.parse("2026-11-13T02:00:00Z");

    @Mock
    private NotificationOutboxMapper outboxMapper;

    private SimulatedNotificationPort port;

    @BeforeEach
    void setUp() {
        port = new SimulatedNotificationPort(outboxMapper, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 入队写outbox且载荷不含患者标识() {
        UUID id = port.publish(new NotificationEvent("SCHEDULE_STOPPED", Map.of(
                "scheduleId", "80000000-0000-4000-8000-000000000001",
                "reasonCode", "PATIENT_CONDITION")));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(outboxMapper).insert(eq(id), eq("SCHEDULE_STOPPED"), payload.capture(), eq(NOW));
        assertThat(payload.getValue()).contains("scheduleId", "PATIENT_CONDITION");
        assertThat(payload.getValue()).doesNotContain("patientName", "hisPatientNo", "inpatientNo",
                "idCard", "phone", "diagnosis");
    }

    @Test
    void 模拟投递始终成功() {
        assertThat(port.deliver(new NotificationMessage(UUID.randomUUID(), "SCHEDULE_MODIFIED",
                "{}"))).isTrue();
    }

    @Test
    void 非法事件类型拒绝入队() {
        assertThatThrownBy(() -> new NotificationEvent("bad-event", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(outboxMapper, org.mockito.Mockito.never()).insert(any(), any(), any(), any());
    }
}
