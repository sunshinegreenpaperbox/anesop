package com.anesop.api.error;

import com.anesop.api.web.RequestContexts;
import com.anesop.contracts.auth.model.Error;
import com.anesop.contracts.auth.model.ErrorDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;

/**
 * 契约统一错误响应 {@code {code,message,traceId,details}} 的构造与写出。
 *
 * <p>错误信息不含堆栈、SQL 实参、内部路径与患者标识；traceId 与审计记录一致。
 */
public final class ErrorResponses {

    private ErrorResponses() {
    }

    public static Error of(String code, String message, String traceId, List<ErrorDetail> details) {
        Error error = new Error();
        error.setCode(code);
        error.setMessage(message);
        error.setTraceId(traceId);
        error.setDetails(details == null ? new ArrayList<>() : new ArrayList<>(details));
        return error;
    }

    public static Error of(String code, String message, HttpServletRequest request) {
        return of(code, message, RequestContexts.traceId(request), List.of());
    }

    public static ErrorDetail detail(String field, String reason) {
        ErrorDetail detail = new ErrorDetail();
        detail.setField(field);
        detail.setReason(reason);
        return detail;
    }

    public static void write(HttpServletResponse response, ObjectMapper objectMapper, int status, Error error)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), error);
    }
}
