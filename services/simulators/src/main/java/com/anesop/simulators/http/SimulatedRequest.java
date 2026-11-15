package com.anesop.simulators.http;

import java.util.Map;

/**
 * 一次模拟请求（已解析为与业务无关的结构）。
 *
 * @param method     HTTP 方法
 * @param path       请求路径（不含查询串）
 * @param pathParams 路径模板变量（如 {@code {patientNo}}）
 * @param query      原始查询串（可为空字符串）
 * @param body       请求体（UTF-8 文本，可为空字符串）
 */
public record SimulatedRequest(
        String method,
        String path,
        Map<String, String> pathParams,
        String query,
        String body) {

    public SimulatedRequest {
        pathParams = pathParams == null ? Map.of() : Map.copyOf(pathParams);
        query = query == null ? "" : query;
        body = body == null ? "" : body;
    }

    /** 取查询参数（重复参数取第一个；不做 URL 解码之外的额外处理）。 */
    public String queryParam(String name) {
        if (query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            String key = idx < 0 ? pair : pair.substring(0, idx);
            if (!name.equals(java.net.URLDecoder.decode(key, java.nio.charset.StandardCharsets.UTF_8))) {
                continue;
            }
            String value = idx < 0 ? "" : pair.substring(idx + 1);
            return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }
}
