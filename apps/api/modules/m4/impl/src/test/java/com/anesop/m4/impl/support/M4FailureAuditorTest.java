package com.anesop.m4.impl.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.anesop.m0.api.audit.AuditWriter;
import com.anesop.m0.api.security.AuthPrincipal;
import com.anesop.m0.api.security.AuthRequestContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * M4 失败路径审计单测：独立事务写 result=0；审计写入失败不得覆盖原始业务异常（S-2）。
 */
@ExtendWith(MockitoExtension.class)
class M4FailureAuditorTest {

    private static final UUID ACTOR = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final AuthPrincipal CALLER =
            new AuthPrincipal(ACTOR, "synth_user", "jti-1", Instant.now().plusSeconds(900), "sid-1");
    private static final AuthRequestContext CONTEXT =
            new AuthRequestContext("127.0.0.1", "JUnit", "SYNTH-TRACE-9001");

    @Mock
    private AuditWriter auditWriter;

    @Test
    void writesFailureAuditWithResultFalseAndCopiedDetail() {
        M4FailureAuditor auditor = new M4FailureAuditor(auditWriter);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "STAFF_CREATE");
        detail.put("rejection", "STATE_CONFLICT");

        auditor.recordFailure("STAFF_CREATE", "STAFF", "NEW", detail, CALLER, CONTEXT);

        verify(auditWriter).record(eq("STAFF_CREATE"), eq("STAFF"), eq("NEW"), eq(false),
                eq(detail), eq(ACTOR), eq("synth_user"), eq(CONTEXT));
    }

    @Test
    void nullDetailAndCallerAreTolerated() {
        M4FailureAuditor auditor = new M4FailureAuditor(auditWriter);

        auditor.recordFailure("SCHEDULE_CREATE", "STAFF_SCHEDULE", "NEW", null, null, CONTEXT);

        verify(auditWriter).record(eq("SCHEDULE_CREATE"), eq("STAFF_SCHEDULE"), eq("NEW"),
                eq(false), anyMap(), eq(null), eq(null), eq(CONTEXT));
    }

    @Test
    void auditFailureDoesNotMaskBusinessException() {
        M4FailureAuditor auditor = new M4FailureAuditor(auditWriter);
        doThrow(new IllegalStateException("db down")).when(auditWriter)
                .record(any(), any(), any(), anyBoolean(), anyMap(), any(), any(), any());

        assertThatCode(() -> auditor.recordFailure("TRAINING_RECORD", "STAFF_TRAINING", "NEW",
                Map.of("operation", "TRAINING_RECORD"), CALLER, CONTEXT))
                .doesNotThrowAnyException();
    }
}
