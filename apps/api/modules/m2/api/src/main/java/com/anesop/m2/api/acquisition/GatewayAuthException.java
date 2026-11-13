package com.anesop.m2.api.acquisition;

/**
 * 网关令牌校验失败（401，fail-closed）。
 *
 * <p>用于 {@code POST /v1/devices/{gatewayId}/{deviceId}/ingest}：令牌缺失、不匹配，
 * 或平台未配置网关密钥（配置缺失一律拒绝，不进入业务逻辑）。异常消息固定，
 * 不回显令牌、期望值或配置状态，异常链不携带请求头原文。
 */
public class GatewayAuthException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造异常。
     *
     * @param message 固定文案（不含令牌与配置细节）
     */
    public GatewayAuthException(String message) {
        super(message);
    }
}
