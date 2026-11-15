package com.anesop.simulators.http;

/**
 * 已记录的请求（只用于联调排障：方法/路径/请求体，不记录任何凭据或真实患者数据）。
 *
 * @param method     HTTP 方法
 * @param path       请求路径
 * @param query      查询串
 * @param body       请求体
 * @param occurredAt 记录时间（ISO-8601）
 */
public record RecordedRequest(
        String method,
        String path,
        String query,
        String body,
        String occurredAt) {
}
