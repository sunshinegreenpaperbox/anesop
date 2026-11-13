package com.anesop.m1.impl.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anesop.m1.api.notification.NotificationMessage;
import com.anesop.m1.api.notification.NotificationPort;
import com.anesop.m1.impl.persistence.entity.NotificationOutboxRow;
import com.anesop.m1.impl.persistence.mapper.NotificationOutboxMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * 通知派发单测（M2-M2-02）：成功记 SENT、失败退避、重试耗尽记 FAILED、无待发返回 false。
 * 派发器依赖的端口与 Mapper 用 Mockito 替身；被测对象是真实 {@link NotificationDispatcher}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-11-13T02:00:00Z");
    private static final UUID OUTBOX_ID = UUID.fromString("91000000-0000-4000-8000-000000000001");

    @Mock
    private NotificationOutboxMapper outboxMapper;
    @Mock
    private NotificationPort notificationPort;

    private NotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new NotificationDispatcher(outboxMapper, notificationPort,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private NotificationOutboxRow row(int attempts, String status) {
        NotificationOutboxRow row = new NotificationOutboxRow();
        row.setId(OUTBOX_ID);
        row.setEventType("SCHEDULE_STOPPED");
        row.setPayload("{\"scheduleId\":\"80000000-0000-4000-8000-000000000001\"}");
        row.setStatus(status);
        row.setAttempts(attempts);
        row.setNextAttemptAt(NOW);
        row.setCreatedAt(NOW);
        return row;
    }

    @Test
    void 无待发通知时不调用投递端口() {
        when(outboxMapper.selectPendingOne(NOW)).thenReturn(null);

        assertThat(dispatcher.drainOnce()).isFalse();
        verify(notificationPort, never()).deliver(any());
    }

    @Test
    void 投递成功标记SENT() {
        when(outboxMapper.selectPendingOne(NOW)).thenReturn(row(0, "PENDING"));
        when(notificationPort.deliver(any())).thenReturn(true);

        assertThat(dispatcher.drainOnce()).isTrue();

        ArgumentCaptor<NotificationMessage> messageCaptor =
                ArgumentCaptor.forClass(NotificationMessage.class);
        verify(notificationPort).deliver(messageCaptor.capture());
        assertThat(messageCaptor.getValue().outboxId()).isEqualTo(OUTBOX_ID);
        assertThat(messageCaptor.getValue().eventType()).isEqualTo("SCHEDULE_STOPPED");
        verify(outboxMapper).markSent(OUTBOX_ID, NOW);
        verify(outboxMapper, never()).markFailure(any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any());
    }

    @Test
    void 投递返回失败时按次数退避且不标记SENT() {
        when(outboxMapper.selectPendingOne(NOW)).thenReturn(row(0, "PENDING"));
        when(notificationPort.deliver(any())).thenReturn(false);

        assertThat(dispatcher.drainOnce()).isTrue();

        verify(outboxMapper).markFailure(eq(OUTBOX_ID), eq(1), eq("PENDING"),
                eq(NOW.plusSeconds(60)), eq(NotificationDispatcher.FAILURE_MESSAGE));
        verify(outboxMapper, never()).markSent(any(), any());
    }

    @Test
    void 投递抛异常时记录失败且不泄露异常文案() {
        when(outboxMapper.selectPendingOne(NOW)).thenReturn(row(1, "PENDING"));
        when(notificationPort.deliver(any())).thenThrow(new IllegalStateException("通道异常-含凭据"));

        assertThat(dispatcher.drainOnce()).isTrue();

        verify(outboxMapper).markFailure(eq(OUTBOX_ID), eq(2), eq("PENDING"),
                eq(NOW.plusSeconds(120)), eq(NotificationDispatcher.FAILURE_MESSAGE));
    }

    @Test
    void 重试耗尽标记FAILED() {
        when(outboxMapper.selectPendingOne(NOW)).thenReturn(row(2, "PENDING"));
        when(notificationPort.deliver(any())).thenReturn(false);

        dispatcher.drainOnce();

        verify(outboxMapper).markFailure(eq(OUTBOX_ID), eq(3), eq("FAILED"),
                eq(NOW.plusSeconds(180)), eq(NotificationDispatcher.FAILURE_MESSAGE));
    }

    @Test
    void 批量派发按上限停止() {
        when(outboxMapper.selectPendingOne(NOW))
                .thenReturn(row(0, "PENDING"))
                .thenReturn(row(0, "PENDING"))
                .thenReturn(null);
        when(notificationPort.deliver(any())).thenReturn(true);

        assertThat(dispatcher.drainAll(2)).isEqualTo(2);
        verify(outboxMapper, org.mockito.Mockito.times(2)).markSent(OUTBOX_ID, NOW);
    }
}
