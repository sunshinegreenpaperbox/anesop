package com.anesop.simulators.pharmacy;

import com.anesop.simulators.SimulatorConfig;
import com.anesop.simulators.http.HttpResult;
import com.anesop.simulators.http.SimulatedHttpServer;
import com.anesop.simulators.http.SimulatedRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 药房模拟器（EXT-02，SYNTHETIC）：药品目录、库存、发药结果与适配器在线状态。
 *
 * <p>字段使用药房系统侧命名（{@code drugCode}/{@code commonName}/{@code narcoticFlag} 等），
 * 与药品域契约不同名，用于验证适配器字段映射（映射表见 {@code docs/integration/README.md} §4.1）。
 * 发药为无状态模拟：不修改库存，按请求量是否超过批次库存返回成功/不足。
 *
 * <p>端点：
 * <ul>
 *   <li>{@code GET /pharmacy/v1/drugs?keyword=} — 药品目录</li>
 *   <li>{@code GET /pharmacy/v1/stock/{drugCode}} — 批次库存</li>
 *   <li>{@code POST /pharmacy/v1/dispense} — 发药结果（含库存不足 409）</li>
 *   <li>{@code GET /pharmacy/v1/adapter-status} — 外部系统在线状态</li>
 * </ul>
 */
public final class PharmacySimulator implements AutoCloseable {

    public static final String SYSTEM = "PMS-HOSPITAL";
    public static final String SYSTEM_NAME = "药房系统（模拟）";
    public static final String LAST_SYNC_AT = "2026-11-14T08:00:00+08:00";
    public static final String BATCH_NO = "SYNTH-BATCH-0001";
    public static final int STOCK_QUANTITY = 120;

    private final SimulatedHttpServer server;
    private final List<PharmacyDrug> drugs = List.of(
            new PharmacyDrug("PMS-D-0001", "舒芬太尼注射液（药房模拟）", "枸橼酸舒芬太尼（药房模拟）",
                    "50ug/1ml", "支", "INJECTION", "OPIOID", 1, 1, List.of("舒芬（药房模拟）")),
            new PharmacyDrug("PMS-D-0002", "丙泊酚乳状注射液（药房模拟）", "丙泊酚（药房模拟）",
                    "200mg/20ml", "支", "INJECTION", "SEDATIVE", 0, 1, List.of("丙泊酚（药房模拟）")),
            new PharmacyDrug("PMS-D-0003", "罗库溴铵注射液（药房模拟）", "罗库溴铵（药房模拟）",
                    "50mg/5ml", "支", "INJECTION", "MUSCLE_RELAXANT", 0, 0, List.of()));

    public PharmacySimulator(SimulatorConfig.Endpoint config) {
        this.server = new SimulatedHttpServer("pharmacy", config);
        server.get("/pharmacy/v1/drugs", this::catalog);
        server.get("/pharmacy/v1/stock/{drugCode}", this::stock);
        server.post("/pharmacy/v1/dispense", this::dispense);
        server.get("/pharmacy/v1/adapter-status", this::adapterStatus);
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
    public List<PharmacyDrug> drugs() {
        return drugs;
    }

    @Override
    public void close() {
        server.close();
    }

    private HttpResult catalog(SimulatedRequest request) {
        String keyword = request.queryParam("keyword");
        List<PharmacyDrug> matched = new ArrayList<>();
        for (PharmacyDrug drug : drugs) {
            if (keyword == null || keyword.isBlank() || matches(drug, keyword)) {
                matched.add(drug);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("system", SYSTEM);
        body.put("synthetic", true);
        body.put("total", matched.size());
        body.put("drugs", matched);
        return HttpResult.ok(body);
    }

    private static boolean matches(PharmacyDrug drug, String keyword) {
        String needle = keyword.trim().toLowerCase(Locale.ROOT);
        if (drug.drugCode().toLowerCase(Locale.ROOT).contains(needle)
                || drug.drugName().toLowerCase(Locale.ROOT).contains(needle)
                || drug.commonName().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        return drug.aliasList().stream()
                .anyMatch(alias -> alias.toLowerCase(Locale.ROOT).contains(needle));
    }

    private HttpResult stock(SimulatedRequest request) {
        PharmacyDrug drug = findDrug(request.pathParams().get("drugCode"));
        if (drug == null) {
            return HttpResult.json(404, error("DRUG_NOT_FOUND", "药房无此药品"));
        }
        return HttpResult.ok(stockOf(drug));
    }

    private HttpResult dispense(SimulatedRequest request) throws IOException {
        JsonNode body = parseBody(request);
        String prescriptionNo = text(body, "prescriptionNo");
        String drugCode = text(body, "drugCode");
        Integer quantity = integer(body, "quantity");
        if (prescriptionNo == null || drugCode == null || quantity == null || quantity <= 0) {
            return HttpResult.json(400, error("INVALID_DISPENSE_REQUEST",
                    "prescriptionNo/drugCode/quantity(>0) 必填"));
        }
        PharmacyDrug drug = findDrug(drugCode);
        if (drug == null) {
            return HttpResult.json(404, error("DRUG_NOT_FOUND", "药房无此药品: " + drugCode));
        }
        if (quantity > STOCK_QUANTITY) {
            Map<String, Object> failure = error("INSUFFICIENT_STOCK", "批次库存不足");
            failure.put("drugCode", drugCode);
            failure.put("available", STOCK_QUANTITY);
            failure.put("requested", quantity);
            return HttpResult.json(409, failure);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("prescriptionNo", prescriptionNo);
        result.put("drugCode", drugCode);
        result.put("dispensed", true);
        result.put("quantity", quantity);
        result.put("batchNo", BATCH_NO);
        result.put("dispensedAt", "2026-11-14T09:30:00+08:00");
        result.put("synthetic", true);
        return HttpResult.ok(result);
    }

    private HttpResult adapterStatus(SimulatedRequest request) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("system", SYSTEM);
        status.put("systemName", SYSTEM_NAME);
        status.put("online", true);
        status.put("pendingOrders", 0);
        status.put("lastSyncAt", LAST_SYNC_AT);
        status.put("message", "药房系统模拟在线（SYNTHETIC）");
        return HttpResult.ok(status);
    }

    private PharmacyStock stockOf(PharmacyDrug drug) {
        return new PharmacyStock(drug.drugCode(), drug.drugName(), BATCH_NO, STOCK_QUANTITY,
                "2028-12-31", "药房（合成）");
    }

    private PharmacyDrug findDrug(String drugCode) {
        if (drugCode == null) {
            return null;
        }
        return drugs.stream().filter(drug -> drug.drugCode().equals(drugCode)).findFirst().orElse(null);
    }

    private JsonNode parseBody(SimulatedRequest request) throws IOException {
        return server.parseJson(request.body());
    }

    private static String text(JsonNode body, String field) {
        if (body == null) {
            return null;
        }
        JsonNode node = body.path(field);
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static Integer integer(JsonNode body, String field) {
        if (body == null) {
            return null;
        }
        JsonNode node = body.path(field);
        return node.isInt() ? node.asInt() : null;
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("synthetic", true);
        return body;
    }

    /** 药房药品目录字段（药房侧命名）。 */
    public record PharmacyDrug(
            String drugCode,
            String drugName,
            String commonName,
            String spec,
            String unit,
            String doseForm,
            String drugClass,
            int narcoticFlag,
            int highRiskFlag,
            List<String> aliasList) {

        public PharmacyDrug {
            aliasList = aliasList == null ? List.of() : List.copyOf(aliasList);
        }
    }

    /** 药房批次库存字段。 */
    public record PharmacyStock(
            String drugCode,
            String drugName,
            String batchNo,
            int quantity,
            String expiryDate,
            String location) {
    }
}
