package com.anesop.simulators.support;

import com.anesop.simulators.FailureMode;
import com.anesop.simulators.SimulatorConfig;
import com.anesop.simulators.SimulatorJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 测试用 HTTP 客户端（JDK HttpClient，无第三方依赖）。 */
public final class TestHttp {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private static final ObjectMapper MAPPER = SimulatorJson.mapper();

    private TestHttp() {
    }

    public static Response get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    public static Response post(String url, String json) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
    }

    private static Response send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> response = CLIENT.send(
                    builder.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (IOException failure) {
            throw new IllegalStateException("测试 HTTP 调用失败: " + failure.getMessage(), failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试 HTTP 调用被中断", interrupted);
        }
    }

    public record Response(int status, String body) {

        public JsonNode json() {
            try {
                return MAPPER.readTree(body);
            } catch (IOException failure) {
                throw new IllegalStateException("响应不是合法 JSON: " + body, failure);
            }
        }

        public String text(String field) {
            return json().path(field).asText();
        }
    }

    public static SimulatorConfig.Endpoint endpoint(int port) {
        SimulatorConfig.Endpoint endpoint = new SimulatorConfig.Endpoint(port);
        return endpoint;
    }

    public static SimulatorConfig.Endpoint failing(int port, FailureMode mode, int failFirstRequests) {
        SimulatorConfig.Endpoint endpoint = endpoint(port);
        endpoint.setFailureMode(mode);
        endpoint.setFailFirstRequests(failFirstRequests);
        endpoint.setErrorStatus(503);
        return endpoint;
    }
}
