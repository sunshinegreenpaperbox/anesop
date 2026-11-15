package com.anesop.simulators.http;

/** 路由处理器：入参是解析后的请求，出参是响应；允许抛受检异常（由服务器转 500）。 */
@FunctionalInterface
public interface RouteHandler {

    /**
     * 处理一次请求。
     *
     * @param request 请求
     * @return 响应
     * @throws Exception 处理失败（服务器返回 500 + SIMULATOR_ERROR）
     */
    HttpResult handle(SimulatedRequest request) throws Exception;
}
