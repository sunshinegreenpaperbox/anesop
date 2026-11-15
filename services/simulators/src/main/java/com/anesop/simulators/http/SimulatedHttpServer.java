package com.anesop.simulators.http;

import com.anesop.simulators.FailureMode;
import com.anesop.simulators.SimulatorConfig;
import com.anesop.simulators.SimulatorJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单个模拟外部系统的 HTTP 服务器（JDK 内置 {@link HttpServer}，无第三方容器）。
 *
 * <p>职责：路由、路径参数、请求留痕、可控延迟，以及故障注入（固定错误码 / 前 N 次失败 /
 * 非法 JSON）。只绑定回环地址，不对院区网络暴露（模拟器不得进入生产部署）。
 *
 * <p>并发模型：每个请求一个虚拟线程；请求计数与留痕线程安全。
 */
public final class SimulatedHttpServer implements AutoCloseable {

    private static final int MAX_RECORDED_REQUESTS = 200;
    private static final int MAX_REPORTED_REQUESTS = 20;
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);

    private final String name;
    private final ObjectMapper mapper;
    private final FailureMode failureMode;
    private final int latencyMs;
    private final int failFirstRequests;
    private final int errorStatus;
    private final int requestedPort;
    private final Map<RouteKey, Route> routes = new LinkedHashMap<>();
    private final List<RecordedRequest> requests = new ArrayList<>();
    private final AtomicInteger requestCount = new AtomicInteger();

    private HttpServer server;
    private ExecutorService executor;
    private int port = -1;

    public SimulatedHttpServer(String name, SimulatorConfig.Endpoint config) {
        this.name = name;
        this.mapper = SimulatorJson.mapper();
        this.failureMode = config.getFailureMode() == null ? FailureMode.NONE : config.getFailureMode();
        this.latencyMs = config.getLatencyMs();
        this.failFirstRequests = config.getFailFirstRequests();
        this.errorStatus = config.getErrorStatus();
        this.requestedPort = config.getPort();
        registerRoutes();
    }

    private void registerRoutes() {
        register("GET", "/sim/v1/requests", this::introspection);
    }

    /** 注册 GET 路由；{@code {name}} 为路径变量。 */
    public void get(String pattern, RouteHandler handler) {
        register("GET", pattern, handler);
    }

    /** 注册 POST 路由；{@code {name}} 为路径变量。 */
    public void post(String pattern, RouteHandler handler) {
        register("POST", pattern, handler);
    }

    private void register(String method, String pattern, RouteHandler handler) {
        List<String> segments = split(pattern);
        routes.put(new RouteKey(method, segments), new Route(method, segments, handler));
    }

    /** 启动服务器（幂等保护：重复 start 视为编程错误）。 */
    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("模拟服务器已启动: " + name);
        }
        server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), requestedPort), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", this::handleExchange);
        server.start();
        port = server.getAddress().getPort();
    }

    /**
     * 解析请求体 JSON（每个服务器自持 ObjectMapper，避免共享可变配置对象）。
     *
     * @param body 请求体
     * @return JSON 树；空请求体返回 null
     * @throws IOException 非法 JSON
     */
    public JsonNode parseJson(String body) throws IOException {
        if (body == null || body.isBlank()) {
            return null;
        }
        return mapper.readTree(body);
    }

    /** 实际监听端口（配置端口为 0 时为系统分配端口）。 */
    public int port() {
        if (port < 0) {
            throw new IllegalStateException("模拟服务器尚未启动: " + name);
        }
        return port;
    }

    /** 基础 URL，形如 {@code http://127.0.0.1:18081}。 */
    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    /** 服务器名（his/pharmacy/spd）。 */
    public String name() {
        return name;
    }

    /** 已处理请求数（含故障注入请求）。 */
    public int requestCount() {
        return requestCount.get();
    }

    /** 最近请求留痕（不可变快照）。 */
    public List<RecordedRequest> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    /** 清空计数与留痕（测试复用同一服务器时使用）。 */
    public void reset() {
        requestCount.set(0);
        synchronized (requests) {
            requests.clear();
        }
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private void handleExchange(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        record(exchange.getRequestMethod(), path, query, body);

        int count = requestCount.incrementAndGet();
        try {
            sleepLatency();
            if (failureMode == FailureMode.HTTP_ERROR || (failFirstRequests > 0 && count <= failFirstRequests)) {
                writeJson(exchange, errorStatus, errorBody("SIMULATED_UPSTREAM_ERROR", "模拟外部系统故障（SYNTHETIC）"));
                return;
            }
            if (failureMode == FailureMode.MALFORMED_JSON) {
                writeRaw(exchange, 200, "{\"broken\": ");
                return;
            }
            RouteMatch match = match(exchange.getRequestMethod(), path);
            if (match == null) {
                writeJson(exchange, 404, errorBody("NOT_FOUND", "模拟器无此端点: " + exchange.getRequestMethod() + " " + path));
                return;
            }
            SimulatedRequest request = new SimulatedRequest(
                    exchange.getRequestMethod(), path, match.params(), query, body);
            HttpResult result = match.route().handler().handle(request);
            if (result.raw()) {
                writeRaw(exchange, result.status(), String.valueOf(result.body()));
            } else {
                writeJson(exchange, result.status(), result.body());
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            writeJson(exchange, 503, errorBody("SIMULATED_INTERRUPTED", "模拟器处理被中断"));
        } catch (IOException ioFailure) {
            throw ioFailure;
        } catch (Exception failure) {
            writeJson(exchange, 500,
                    errorBody("SIMULATOR_ERROR", "模拟器内部错误: " + failure.getClass().getSimpleName()));
        }
    }

    private HttpResult introspection(SimulatedRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("server", name);
        body.put("synthetic", true);
        body.put("requestCount", requestCount.get());
        List<RecordedRequest> recent;
        synchronized (requests) {
            int from = Math.max(0, requests.size() - MAX_REPORTED_REQUESTS);
            recent = List.copyOf(requests.subList(from, requests.size()));
        }
        body.put("requests", recent);
        return HttpResult.ok(body);
    }

    private void record(String method, String path, String query, String body) {
        RecordedRequest recorded = new RecordedRequest(method, path, query == null ? "" : query, body,
                OffsetDateTime.now(DISPLAY_OFFSET).toString());
        synchronized (requests) {
            requests.add(recorded);
            if (requests.size() > MAX_RECORDED_REQUESTS) {
                requests.remove(0);
            }
        }
    }

    private void sleepLatency() throws InterruptedException {
        if (latencyMs > 0) {
            Thread.sleep(latencyMs);
        }
    }

    private static Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("synthetic", true);
        return body;
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        writeRaw(exchange, status, mapper.writeValueAsString(body));
    }

    private void writeRaw(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private RouteMatch match(String method, String path) {
        List<String> parts = split(path);
        for (Route route : routes.values()) {
            if (!route.method().equals(method) || route.segments().size() != parts.size()) {
                continue;
            }
            Map<String, String> params = new LinkedHashMap<>();
            boolean matched = true;
            for (int index = 0; index < parts.size(); index++) {
                String pattern = route.segments().get(index);
                if (pattern.startsWith("{") && pattern.endsWith("}")) {
                    params.put(pattern.substring(1, pattern.length() - 1), parts.get(index));
                } else if (!pattern.equals(parts.get(index))) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return new RouteMatch(route, Map.copyOf(params));
            }
        }
        return null;
    }

    private static List<String> split(String path) {
        String trimmed = path == null ? "" : path.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? List.of() : List.of(trimmed.split("/"));
    }

    private record RouteKey(String method, List<String> segments) {
    }

    private record Route(String method, List<String> segments, RouteHandler handler) {
    }

    private record RouteMatch(Route route, Map<String, String> params) {
    }
}
