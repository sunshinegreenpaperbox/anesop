package com.anesop.gateway.upload;

import com.anesop.gateway.model.VitalSample;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台上报适配器（HTTP，M3-M8-01）：把本地缓冲的一批标准化体征上报
 * {@code POST /v1/devices/{gatewayId}/{deviceId}/ingest}。
 *
 * <p>默认不使用（{@code anesop.gateway.platform.mode=jdbc} 仍直连 PostgreSQL，POC 简化）；
 * 配置切换后网关不再直连业务库，改由平台统一做幂等、质量统计与审计。真实设备协议与
 * 平台侧采集接入仍属 EXT-04（SIMULATED，待 D-06 设备清单与现场勘查）。
 *
 * <p>幂等与失败语义：
 * <ul>
 *   <li>batchKey 由网关按 {@code gatewayId-deviceId-首帧seq-末帧seq-首帧毫秒} 确定性生成，
 *       同一批重试复用同一键 → 平台判定重复批次，不会重复入库；</li>
 *   <li>非 200/202 响应或无法解析响应体一律抛 {@link IOException}：上传器<b>不确认</b>该批，
 *       数据保留在本地缓冲并指数退避重试（宁可重复上报依赖幂等，也不丢数据）；</li>
 *   <li>{@code X-Gateway-Token} 只放在请求头，不写日志、不进异常消息。</li>
 * </ul>
 */
public final class PlatformVitalReporter implements VitalSink {

    private static final String HEADER_TOKEN = "X-Gateway-Token";
    private static final int HTTP_OK = 200;
    private static final int HTTP_ACCEPTED = 202;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String gatewayId;
    private final String token;
    private final Duration timeout;
    private final ObjectMapper objectMapper;

    /**
     * 构造上报适配器。
     *
     * @param baseUrl      平台 API 基地址（如 {@code https://anesop.local/api}，末尾斜杠会被去掉）
     * @param gatewayId    网关实例标识（路径与 batchKey 前缀）
     * @param token        内部网关令牌（非空白；空令牌在配置校验阶段即拒绝启动）
     * @param timeoutMs    单次请求超时（毫秒，>0）
     */
    public PlatformVitalReporter(String baseUrl, String gatewayId, String token, int timeoutMs) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("platform.url 不能为空");
        }
        if (gatewayId == null || gatewayId.isBlank()) {
            throw new IllegalArgumentException("gateway-id 不能为空");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("platform.token 不能为空（fail-closed）");
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("platform.timeout-ms 必须为正");
        }
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.gatewayId = gatewayId;
        this.token = token;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public int insertAll(List<VitalSample> samples) throws IOException {
        if (samples.isEmpty()) {
            return 0;
        }
        String deviceId = samples.get(0).deviceId();
        for (VitalSample sample : samples) {
            if (!deviceId.equals(sample.deviceId())) {
                throw new IOException("同一批样本必须来自同一设备");
            }
        }
        String body = toJson(samples);
        HttpRequest request = HttpRequest.newBuilder(ingestUri(deviceId))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header(HEADER_TOKEN, token)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("平台上报被中断", interrupted);
        }
        int status = response.statusCode();
        if (status != HTTP_OK && status != HTTP_ACCEPTED) {
            // 只记录状态码，避免把响应体（可能含内部信息）写入日志/异常链。
            throw new IOException("平台上报失败 httpStatus=" + status);
        }
        return readAcceptedRows(response.body());
    }

    @Override
    public boolean ping() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health"))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (IOException | InterruptedException unavailable) {
            if (unavailable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * 本批的确定性批次键（同一批重试必须一致）。
     *
     * @param samples 同一设备的样本
     * @return 批次键
     */
    static String batchKey(String gatewayId, List<VitalSample> samples) {
        VitalSample first = samples.get(0);
        VitalSample last = samples.get(samples.size() - 1);
        return gatewayId + "-" + first.deviceId() + "-" + first.seq() + "-" + last.seq()
                + "-" + first.measuredAt().toEpochMilli();
    }

    private URI ingestUri(String deviceId) {
        return URI.create(baseUrl + "/v1/devices/" + gatewayId + "/" + deviceId + "/ingest");
    }

    private String toJson(List<VitalSample> samples) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchKey", batchKey(gatewayId, samples));
        List<Map<String, Object>> frames = new ArrayList<>(samples.size());
        for (VitalSample sample : samples) {
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("seq", sample.seq());
            frame.put("measuredAt", sample.measuredAt());
            frame.put("measuredAtCorrected", sample.measuredAtCorrected());
            frame.put("receivedAt", sample.receivedAt());
            frame.put("hr", sample.hr());
            frame.put("spo2", sample.spo2());
            frame.put("nibpSys", sample.nibpSys());
            frame.put("nibpDia", sample.nibpDia());
            frame.put("etco2", sample.etco2());
            frame.put("temp", sample.temp());
            frame.put("quality", sample.quality());
            frame.put("flags", sample.qualityFlags());
            frame.put("source", sample.source());
            frames.add(frame);
        }
        payload.put("frames", frames);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException serializationFailure) {
            throw new IOException("体征批次序列化失败", serializationFailure);
        }
    }

    private int readAcceptedRows(String body) throws IOException {
        if (body == null || body.isBlank()) {
            // 响应体缺失时无法确认平台是否已接收：按失败处理，重试由幂等兜底。
            throw new IOException("平台上报响应为空");
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode accepted = node.get("acceptedRows");
            if (accepted == null || !accepted.canConvertToInt()) {
                throw new IOException("平台上报响应缺少 acceptedRows");
            }
            return accepted.asInt();
        } catch (JsonProcessingException invalidBody) {
            throw new IOException("平台上报响应无法解析", invalidBody);
        }
    }
}
