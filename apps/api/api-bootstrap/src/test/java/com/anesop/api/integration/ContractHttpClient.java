package com.anesop.api.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 契约测试用“参考适配器”HTTP 客户端（M5-M19-01）。
 *
 * <p>该客户端固化方案书 §3.5.1 的外部接口调用语义，供适配器↔模拟器契约测试断言：
 * <ul>
 *   <li>单次请求超时（连接 + 读取）；</li>
 *   <li>5xx 与 IO 异常最多重试 {@code maxAttempts} 次，指数退避；<b>4xx 不重试</b>
 *       （业务拒绝不是瞬时故障）；</li>
 *   <li>重试耗尽返回失败结果（{@code success=false}），由适配器映射为“降级”状态，
 *       不把异常抛给临床主链路；</li>
 *   <li>记录实际尝试次数，契约测试据此断言“重试/不重试/超时”行为。</li>
 * </ul>
 *
 * <p>仅测试作用域，不进入交付物；生产适配器接入真实接口时按同一语义实现。
 */
final class ContractHttpClient {

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Duration requestTimeout;
    private final int maxAttempts;
    private final long backoffMillis;

    ContractHttpClient(ObjectMapper mapper, Duration connectTimeout, Duration requestTimeout,
            int maxAttempts, long backoffMillis) {
        this.mapper = mapper;
        this.requestTimeout = requestTimeout;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMillis = Math.max(0L, backoffMillis);
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    Result get(String url) {
        return execute("GET", url, null);
    }

    Result post(String url, String jsonBody) {
        return execute("POST", url, jsonBody);
    }

    private Result execute(String method, String url, String jsonBody) {
        String lastFailure = null;
        int lastStatus = 0;
        JsonNode lastBody = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                        .timeout(requestTimeout)
                        .header("Accept", "application/json");
                if ("POST".equals(method)) {
                    builder.header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    jsonBody == null ? "" : jsonBody, StandardCharsets.UTF_8));
                } else {
                    builder.GET();
                }
                HttpResponse<String> response = client.send(builder.build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                lastStatus = response.statusCode();
                lastBody = parse(response.body());
                if (lastStatus >= 500) {
                    lastFailure = "HTTP " + lastStatus + "（模拟外部系统故障）";
                } else {
                    return new Result(true, lastStatus, lastBody, attempt, null);
                }
            } catch (IOException ioFailure) {
                lastFailure = ioFailure.getClass().getSimpleName()
                        + (ioFailure.getMessage() == null ? "" : ": " + ioFailure.getMessage());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new Result(false, 0, null, attempt, "INTERRUPTED");
            }
            if (attempt < maxAttempts) {
                backoff(attempt);
            }
        }
        return new Result(false, lastStatus, lastBody, maxAttempts, lastFailure);
    }

    private void backoff(int attempt) {
        if (backoffMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMillis * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (IOException malformed) {
            // 模拟器故障注入会返回非法 JSON；解析失败按“上游不可用”处理（不重试解析）。
            return null;
        }
    }

    /**
     * 调用结果。
     *
     * @param delivered     是否收到可解析响应（2xx/3xx/4xx 为 true；5xx 重试耗尽、超时、连接失败为 false）
     * @param status        HTTP 状态码（IO 失败为 0）
     * @param body          JSON 响应体（非法 JSON 为 null）
     * @param attempts      实际尝试次数
     * @param failureReason 失败原因（成功为 null）
     */
    record Result(boolean delivered, int status, JsonNode body, int attempts, String failureReason) {

        boolean retried() {
            return attempts > 1;
        }

        String text(String field) {
            if (body == null) {
                throw new IllegalStateException("响应体为空，无法读取字段: " + field);
            }
            return body.path(field).asText();
        }
    }
}
