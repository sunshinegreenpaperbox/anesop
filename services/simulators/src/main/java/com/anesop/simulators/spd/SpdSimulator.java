package com.anesop.simulators.spd;

import com.anesop.simulators.SimulatorConfig;
import com.anesop.simulators.http.HttpResult;
import com.anesop.simulators.http.SimulatedHttpServer;
import com.anesop.simulators.http.SimulatedRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SPD 模拟器（EXT-03，SYNTHETIC）：耗材目录同步、同步回执与系统在线状态。
 *
 * <p>字段使用 SPD 侧命名（{@code materialCode}/{@code highValueFlag}/{@code pendingReceipts} 等），
 * 与耗材域契约不同名，用于验证适配器字段映射（映射表见 {@code docs/integration/README.md} §5.1）。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code GET /spd/v1/materials} — 供应商耗材目录</li>
 *   <li>{@code POST /spd/v1/sync} — 同步（以 idempotencyKey 幂等：同键返回同一回执）</li>
 *   <li>{@code GET /spd/v1/adapter-status} — 系统在线状态</li>
 * </ul>
 */
public final class SpdSimulator implements AutoCloseable {

    public static final String SYSTEM = "SPD-HOSPITAL";
    public static final String SYSTEM_NAME = "SPD 供应链系统（模拟）";
    public static final String LAST_SYNC_AT = "2026-11-14T07:00:00+08:00";
    public static final int PULLED_COUNT = 2;
    public static final int PUSHED_COUNT = 0;
    private static final ZoneOffset DISPLAY_OFFSET = ZoneOffset.ofHours(8);

    private final SimulatedHttpServer server;
    private final List<SpdMaterial> materials = List.of(
            new SpdMaterial("SPD-M-0001", "中心静脉导管（演示）", "7F 三腔", "套", "CATHETER",
                    "UDI-DI-SYNTH-0001", 1, 1, "SYNTHETIC SPD 目录"),
            new SpdMaterial("SPD-M-0002", "一次性使用麻醉穿刺包（演示）", "常规型", "包", "ANESTHESIA_KIT",
                    "UDI-DI-SYNTH-0002", 0, 1, "SYNTHETIC SPD 目录"));
    private final Map<String, SpdSyncAck> syncReceipts = new LinkedHashMap<>();

    public SpdSimulator(SimulatorConfig.Endpoint config) {
        this.server = new SimulatedHttpServer("spd", config);
        server.get("/spd/v1/materials", this::materialCatalog);
        server.post("/spd/v1/sync", this::sync);
        server.get("/spd/v1/adapter-status", this::adapterStatus);
    }

    public void start() throws IOException {
        server.start();
    }

    public int port() {
        return server.port();
    }

    public String baseUrl() {
        return server.baseUrl();
    }

    /** 已处理请求数（契约测试/脚本排障）。 */
    public int requestCount() {
        return server.requestCount();
    }

    /** 目录数据（不可变快照，供契约测试断言映射源）。 */
    public List<SpdMaterial> materials() {
        return materials;
    }

    @Override
    public void close() {
        server.close();
    }

    private HttpResult materialCatalog(SimulatedRequest request) {
        String keyword = request.queryParam("keyword");
        List<SpdMaterial> matched = materials.stream()
                .filter(material -> keyword == null || keyword.isBlank()
                        || material.materialCode().toLowerCase(Locale.ROOT)
                                .contains(keyword.trim().toLowerCase(Locale.ROOT))
                        || material.materialName().toLowerCase(Locale.ROOT)
                                .contains(keyword.trim().toLowerCase(Locale.ROOT)))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("system", SYSTEM);
        body.put("synthetic", true);
        body.put("total", matched.size());
        body.put("materials", matched);
        return HttpResult.ok(body);
    }

    private HttpResult sync(SimulatedRequest request) throws IOException {
        JsonNode body = parseBody(request);
        String idempotencyKey = body == null ? null : body.path("idempotencyKey").asText(null);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return HttpResult.json(400, error("INVALID_SYNC_REQUEST", "idempotencyKey 必填（幂等键）"));
        }
        SpdSyncAck ack = receiptFor(idempotencyKey);
        return HttpResult.ok(ack);
    }

    private SpdSyncAck receiptFor(String idempotencyKey) {
        synchronized (syncReceipts) {
            SpdSyncAck existing = syncReceipts.get(idempotencyKey);
            if (existing != null) {
                return existing;
            }
            SpdSyncAck created = new SpdSyncAck(
                    "SPD-SYNC-" + String.format("%04d", syncReceipts.size() + 1),
                    true,
                    PULLED_COUNT,
                    PUSHED_COUNT,
                    OffsetDateTime.now(DISPLAY_OFFSET).toString(),
                    "SPD 模拟同步完成（SYNTHETIC）",
                    idempotencyKey);
            syncReceipts.put(idempotencyKey, created);
            return created;
        }
    }

    private HttpResult adapterStatus(SimulatedRequest request) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("system", SYSTEM);
        status.put("systemName", SYSTEM_NAME);
        status.put("online", true);
        status.put("pendingReceipts", 0);
        status.put("lastSyncAt", LAST_SYNC_AT);
        status.put("message", "SPD 系统模拟在线（SYNTHETIC）");
        return HttpResult.ok(status);
    }

    private JsonNode parseBody(SimulatedRequest request) throws IOException {
        return server.parseJson(request.body());
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("synthetic", true);
        return body;
    }

    /** SPD 耗材目录字段（SPD 侧命名）。 */
    public record SpdMaterial(
            String materialCode,
            String materialName,
            String specification,
            String unit,
            String categoryCode,
            String udiDi,
            int highValueFlag,
            int expiryManagedFlag,
            String remark) {
    }

    /** SPD 同步回执（外部系统侧命名）。 */
    public record SpdSyncAck(
            String syncNo,
            boolean acknowledged,
            int pulled,
            int pushed,
            String syncedAt,
            String message,
            String idempotencyKey) {
    }
}
