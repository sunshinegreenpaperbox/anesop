package com.anesop.m1.impl.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.contracts.platform.model.MessageType;
import com.anesop.m0.api.platform.MessagePublisher;
import com.anesop.m0.api.platform.MessagePublisher.MessageCommand;
import com.anesop.m0.api.platform.NotificationRecipientDirectory;
import com.anesop.m1.api.notification.NotificationEvent;
import com.anesop.m1.api.notification.NotificationMessage;
import com.anesop.m1.impl.persistence.mapper.NotificationOutboxMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
 * 站内消息通知端口单测（M9-01）：入队 payload 无 PHI、默认通知矩阵按角色投递、
 * 参数白名单、去重键、无接收人不投递、未登记事件跳过。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageCenterNotificationPortTest {

    private static final Instant NOW = Instant.parse("2026-11-15T02:00:00Z");
    private static final UUID OUTBOX_ID = UUID.fromString("91000000-0000-4000-8000-000000000001");
    private static final UUID ANES_USER = UUID.fromString("e0000000-0000-4000-8000-00000000e101");
    private static final UUID OR_USER = UUID.fromString("e0000000-0000-4000-8000-00000000e102");

    @Mock
    private NotificationOutboxMapper outboxMapper;
    @Mock
    private MessagePublisher messagePublisher;
    @Mock
    private NotificationRecipientDirectory recipientDirectory;

    private MessageCenterNotificationPort port;

    @BeforeEach
    void setUp() {
        port = new MessageCenterNotificationPort(outboxMapper, messagePublisher,
                recipientDirectory, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
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
    void 改台事件按通知矩阵投递到两个角色的启用用户() {
        when(recipientDirectory.enabledUserIdsByRole("SYS_ANESTHESIOLOGIST"))
                .thenReturn(List.of(ANES_USER));
        when(recipientDirectory.enabledUserIdsByRole("SYS_OR_MANAGER"))
                .thenReturn(List.of(OR_USER));

        boolean delivered = port.deliver(new NotificationMessage(OUTBOX_ID, "SCHEDULE_MODIFIED",
                "{\"scheduleId\":\"80000000-0000-4000-8000-000000000001\","
                        + "\"roomId\":\"f2000000-0000-4000-8000-0000000000c1\","
                        + "\"startAt\":\"2026-11-16T08:30:00Z\","
                        + "\"reasonCode\":\"SURGEON_REQUEST\","
                        + "\"patientName\":\"演示患者\",\"inpatientNo\":\"ZY-SYNTH-1\"}"));

        assertThat(delivered).isTrue();
        ArgumentCaptor<MessageCommand> commands = ArgumentCaptor.forClass(MessageCommand.class);
        verify(messagePublisher, org.mockito.Mockito.times(2)).publish(commands.capture());
        assertThat(commands.getAllValues()).extracting(MessageCommand::recipientUserId)
                .containsExactlyInAnyOrder(ANES_USER, OR_USER);
        MessageCommand first = commands.getAllValues().get(0);
        assertThat(first.type()).isEqualTo(MessageType.SCHEDULE);
        assertThat(first.templateCode()).isEqualTo("SCHEDULE_CHANGE");
        assertThat(first.params()).containsEntry("roomId", "f2000000-0000-4000-8000-0000000000c1")
                .containsEntry("reasonCode", "SURGEON_REQUEST")
                .doesNotContainKeys("patientName", "inpatientNo");
        assertThat(first.dedupeKey()).startsWith("OUTBOX:" + OUTBOX_ID + ":").contains(
                "SCHEDULE_CHANGE");
    }

    @Test
    void 未确认严重告警投递到麻醉角色且参数无PHI() {
        when(recipientDirectory.enabledUserIdsByRole("SYS_ANESTHESIOLOGIST"))
                .thenReturn(List.of(ANES_USER));
        when(recipientDirectory.enabledUserIdsByRole("SYS_OR_MANAGER")).thenReturn(List.of());

        port.deliver(new NotificationMessage(OUTBOX_ID, "ALERT_CRITICAL",
                "{\"alertId\":\"a1000000-0000-4000-8000-000000000001\","
                        + "\"recordId\":\"12000000-0000-4000-8000-000000000001\","
                        + "\"metric\":\"SPO2\",\"severity\":\"CRITICAL\","
                        + "\"triggeredAt\":\"2026-11-15T02:00:00Z\",\"patientNo\":\"SYNTH-P-0001\"}"));

        ArgumentCaptor<MessageCommand> command = ArgumentCaptor.forClass(MessageCommand.class);
        verify(messagePublisher).publish(command.capture());
        assertThat(command.getValue().type()).isEqualTo(MessageType.ALERT);
        assertThat(command.getValue().templateCode()).isEqualTo("CRITICAL_ALERT");
        assertThat(command.getValue().params()).containsEntry("metric", "SPO2")
                .containsEntry("severity", "CRITICAL")
                .doesNotContainKeys("recordId", "patientNo");
    }

    @Test
    void 角色下无启用用户时不投递且不报错() {
        when(recipientDirectory.enabledUserIdsByRole(any())).thenReturn(List.of());

        assertThat(port.deliver(new NotificationMessage(OUTBOX_ID, "EMERGENCY_SCHEDULED",
                "{}"))).isTrue();
        verify(messagePublisher, never()).publish(any());
    }

    @Test
    void 未登记事件跳过且不投递() {
        assertThat(port.deliver(new NotificationMessage(OUTBOX_ID, "SYSTEM_BROADCAST",
                "{}"))).isTrue();
        verify(messagePublisher, never()).publish(any());
    }

    @Test
    void 非法事件类型拒绝入队() {
        assertThatThrownBy(() -> new NotificationEvent("bad-event", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(outboxMapper, org.mockito.Mockito.never()).insert(any(), any(), any(), any());
    }
}
