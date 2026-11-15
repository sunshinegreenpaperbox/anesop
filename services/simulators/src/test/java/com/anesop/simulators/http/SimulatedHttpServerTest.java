package com.anesop.simulators.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anesop.simulators.FailureMode;
import com.anesop.simulators.support.TestHttp;
import com.anesop.simulators.support.TestHttp.Response;
import org.junit.jupiter.api.Test;

/** 模拟 HTTP 服务器：路由/路径变量/留痕/故障注入/生命周期。 */
class SimulatedHttpServerTest {

    @Test
    void servesJsonRoutesWithPathVariablesAndRecordsRequests() throws Exception {
        SimulatedHttpServer server = new SimulatedHttpServer("test", TestHttp.endpoint(0));
        server.get("/demo/v1/items/{itemId}", request -> HttpResult.ok(
                java.util.Map.of("id", request.pathParams().get("itemId"),
                        "query", String.valueOf(request.queryParam("q")))));
        server.start();
        try {
            Response response = TestHttp.get(server.baseUrl() + "/demo/v1/items/A-1?q=%E6%BC%94%E7%A4%BA");

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.text("id")).isEqualTo("A-1");
            assertThat(response.text("query")).isEqualTo("演示");
            assertThat(server.port()).isPositive();
            assertThat(server.requestCount()).isEqualTo(1);
            assertThat(server.requests()).hasSize(1);
            assertThat(server.requests().get(0).method()).isEqualTo("GET");
            assertThat(server.requests().get(0).path()).isEqualTo("/demo/v1/items/A-1");
            assertThat(server.requests().get(0).occurredAt()).isNotBlank();
        } finally {
            server.close();
        }
    }

    @Test
    void unknownRouteAndMethodReturn404WithSyntheticErrorBody() throws Exception {
        SimulatedHttpServer server = new SimulatedHttpServer("test", TestHttp.endpoint(0));
        server.get("/demo/v1/items", request -> HttpResult.ok(java.util.Map.of("ok", true)));
        server.start();
        try {
            Response notFound = TestHttp.get(server.baseUrl() + "/demo/v1/unknown");
            assertThat(notFound.status()).isEqualTo(404);
            assertThat(notFound.text("code")).isEqualTo("NOT_FOUND");
            assertThat(notFound.json().path("synthetic").asBoolean()).isTrue();

            Response wrongMethod = TestHttp.post(server.baseUrl() + "/demo/v1/items", "{}");
            assertThat(wrongMethod.status()).isEqualTo(404);
        } finally {
            server.close();
        }
    }

    @Test
    void introspectionReportsCountAndRecentRequestsWithoutSecrets() throws Exception {
        SimulatedHttpServer server = new SimulatedHttpServer("test", TestHttp.endpoint(0));
        server.post("/demo/v1/echo", request -> HttpResult.ok(java.util.Map.of("body", request.body())));
        server.start();
        try {
            TestHttp.post(server.baseUrl() + "/demo/v1/echo", "{\"synthetic\":true}");

            Response introspection = TestHttp.get(server.baseUrl() + "/sim/v1/requests");

            assertThat(introspection.status()).isEqualTo(200);
            assertThat(introspection.text("server")).isEqualTo("test");
            assertThat(introspection.json().path("synthetic").asBoolean()).isTrue();
            assertThat(introspection.json().path("requests").size()).isEqualTo(2);
            assertThat(introspection.body()).doesNotContain("password").doesNotContain("token");
        } finally {
            server.close();
        }
    }

    @Test
    void failureModesInjectHttpErrorMalformedJsonAndFailFirstRequests() throws Exception {
        SimulatedHttpServer error = new SimulatedHttpServer("test",
                TestHttp.failing(0, FailureMode.HTTP_ERROR, 0));
        error.get("/demo/v1/status", request -> HttpResult.ok(java.util.Map.of("online", true)));
        error.start();
        try {
            Response response = TestHttp.get(error.baseUrl() + "/demo/v1/status");
            assertThat(response.status()).isEqualTo(503);
            assertThat(response.text("code")).isEqualTo("SIMULATED_UPSTREAM_ERROR");
        } finally {
            error.close();
        }

        SimulatedHttpServer malformed = new SimulatedHttpServer("test",
                TestHttp.failing(0, FailureMode.MALFORMED_JSON, 0));
        malformed.get("/demo/v1/status", request -> HttpResult.ok(java.util.Map.of("online", true)));
        malformed.start();
        try {
            Response response = TestHttp.get(malformed.baseUrl() + "/demo/v1/status");
            assertThat(response.status()).isEqualTo(200);
            assertThatThrownBy(response::json).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不是合法 JSON");
        } finally {
            malformed.close();
        }

        SimulatedHttpServer transientFailure = new SimulatedHttpServer("test",
                TestHttp.failing(0, FailureMode.NONE, 1));
        transientFailure.get("/demo/v1/status", request -> HttpResult.ok(java.util.Map.of("online", true)));
        transientFailure.start();
        try {
            assertThat(TestHttp.get(transientFailure.baseUrl() + "/demo/v1/status").status()).isEqualTo(503);
            assertThat(TestHttp.get(transientFailure.baseUrl() + "/demo/v1/status").status()).isEqualTo(200);
        } finally {
            transientFailure.close();
        }
    }

    @Test
    void configuredLatencyDelaysResponse() throws Exception {
        com.anesop.simulators.SimulatorConfig.Endpoint endpoint = TestHttp.endpoint(0);
        endpoint.setLatencyMs(60);
        SimulatedHttpServer slow = new SimulatedHttpServer("test", endpoint);
        slow.get("/demo/v1/status", request -> HttpResult.ok(java.util.Map.of("online", true)));
        slow.start();
        try {
            long startedAt = System.nanoTime();
            Response response = TestHttp.get(slow.baseUrl() + "/demo/v1/status");
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

            assertThat(response.status()).isEqualTo(200);
            assertThat(elapsedMs).isGreaterThanOrEqualTo(50L);
        } finally {
            slow.close();
        }
    }

    @Test
    void handlerFailureBecomes500WithoutStackTraceAndRawResultsAreSupported() throws Exception {
        SimulatedHttpServer server = new SimulatedHttpServer("test", TestHttp.endpoint(0));
        server.get("/demo/v1/boom", request -> {
            throw new IllegalStateException("内部异常不得外泄");
        });
        server.get("/demo/v1/raw", request -> HttpResult.raw(202, "raw-body"));
        server.start();
        try {
            Response boom = TestHttp.get(server.baseUrl() + "/demo/v1/boom");
            assertThat(boom.status()).isEqualTo(500);
            assertThat(boom.text("code")).isEqualTo("SIMULATOR_ERROR");
            assertThat(boom.body()).doesNotContain("内部异常不得外泄");

            Response raw = TestHttp.get(server.baseUrl() + "/demo/v1/raw");
            assertThat(raw.status()).isEqualTo(202);
            assertThat(raw.body()).isEqualTo("raw-body");
        } finally {
            server.close();
        }
    }

    @Test
    void lifecycleGuardsDoubleStartAndPortBeforeStart() throws Exception {
        SimulatedHttpServer server = new SimulatedHttpServer("test", TestHttp.endpoint(0));
        assertThatThrownBy(server::port).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未启动");
        assertThat(server.name()).isEqualTo("test");
        server.close();

        SimulatedHttpServer started = new SimulatedHttpServer("test", TestHttp.endpoint(0));
        started.start();
        try {
            assertThatThrownBy(started::start).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("已启动");
            started.reset();
            assertThat(started.requestCount()).isZero();
        } finally {
            started.close();
        }
    }
}
