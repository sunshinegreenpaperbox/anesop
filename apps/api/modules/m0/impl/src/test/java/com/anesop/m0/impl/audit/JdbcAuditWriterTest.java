package com.anesop.m0.impl.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import com.anesop.m0.api.security.AuthRequestContext;
import com.anesop.m0.impl.persistence.entity.SysAuditLog;
import com.anesop.m0.impl.persistence.mapper.SysAuditLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 审计落库单测（M2-M0-03）：IP 归一化（IPv4/IPv6/非法/空）、字段截断、
 * detail 序列化失败兜底与空 detail 的稳定 JSON。
 */
class JdbcAuditWriterTest {

    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
    private static final UUID ACTOR = UUID.fromString("10000000-0000-0000-0000-000000000001");

    private final SysAuditLogMapper mapper = mock(SysAuditLogMapper.class);
    private final JdbcAuditWriter writer = new JdbcAuditWriter(mapper, new ObjectMapper(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void writesIpv4AndTruncatesOversizedFields() {
        writer.record("LOGIN", "USER", "resource-1", true, Map.of("k", "v"), ACTOR,
                "  " + "A".repeat(120) + "  ",
                new AuthRequestContext(" 10.0.0.1 ", "U".repeat(600), "T".repeat(80)));

        SysAuditLog row = capture();
        assertThat(row.getAction()).isEqualTo("LOGIN");
        assertThat(row.getResourceType()).isEqualTo("USER");
        assertThat(row.getResourceId()).isEqualTo("resource-1");
        assertThat(row.getResult()).isEqualTo(1);
        assertThat(row.getIp()).isEqualTo("10.0.0.1");
        assertThat(row.getActorName()).hasSize(100);
        assertThat(row.getUserAgent()).hasSize(512);
        assertThat(row.getTraceId()).hasSize(64);
        assertThat(row.getActorId()).isEqualTo(ACTOR);
        assertThat(row.getOccurredAt()).isEqualTo(NOW);
        assertThat(row.getDetail()).isEqualTo("{\"k\":\"v\"}");
    }

    @Test
    void acceptsIpv6AndRejectsInvalidOrBlankIp() {
        writer.record("LOGOUT", "SESSION", null, false, Map.of(), null, null,
                new AuthRequestContext("2001:db8::1", "ua", "trace"));
        assertThat(capture().getIp()).isEqualTo("2001:db8::1");

        writer.record("LOGOUT", "SESSION", null, false, Map.of(), null, null,
                new AuthRequestContext("not-an-ip:with:colon", null, null));
        SysAuditLog invalid = capture();
        assertThat(invalid.getIp()).isNull();
        assertThat(invalid.getUserAgent()).isNull();
        assertThat(invalid.getTraceId()).isNull();

        writer.record("LOGOUT", "SESSION", null, false, Map.of(), null, "  ",
                new AuthRequestContext(" ", "   ", " "));
        SysAuditLog blank = capture();
        assertThat(blank.getIp()).isNull();
        assertThat(blank.getActorName()).isNull();
        assertThat(blank.getUserAgent()).isNull();
        assertThat(blank.getTraceId()).isNull();
    }

    @Test
    void nullContextAndNullDetailProduceSafeRow() {
        writer.record("LOGIN", "USER", null, false, null, null, null, null);

        SysAuditLog row = capture();
        assertThat(row.getDetail()).isEqualTo("{}");
        assertThat(row.getIp()).isNull();
        assertThat(row.getUserAgent()).isNull();
        assertThat(row.getTraceId()).isNull();
    }

    @Test
    void detailSerializationFailureFallsBackToMinimalStructure() {
        Map<String, Object> selfReferencing = new LinkedHashMap<>();
        selfReferencing.put("self", selfReferencing);

        writer.record("LOGIN", "USER", null, true, selfReferencing, null, null, null);

        assertThat(capture().getDetail()).isEqualTo("{\"detailSerializationError\":true}");
    }

    private SysAuditLog capture() {
        ArgumentCaptor<SysAuditLog> captor = ArgumentCaptor.forClass(SysAuditLog.class);
        verify(mapper, atLeastOnce()).insert(captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1);
    }
}
