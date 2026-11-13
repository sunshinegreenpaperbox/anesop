package com.anesop.m2.impl.acquisition;

import com.anesop.m2.api.acquisition.GatewayAuthException;
import com.anesop.m2.impl.config.AcquisitionProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;

/**
 * 网关令牌校验（{@code X-Gateway-Token}，M3-M8-01）。
 *
 * <p>安全约定：
 * <ul>
 *   <li><b>fail-closed</b>：平台未配置令牌（null/空白）时一律拒绝，绝不"未配置即放行"；</li>
 *   <li>比较使用 {@link MessageDigest#isEqual(byte[], byte[])}，避免按字节短路比较的时序侧信道；
 *       长度不等直接拒绝（只泄露长度，不泄露内容）；</li>
 *   <li>令牌、期望值与配置状态<b>不写入日志/审计/异常消息</b>；异常文案固定，避免探测。</li>
 * </ul>
 */
@Component
public class GatewayTokenVerifier {

    private static final String REJECT_MESSAGE = "网关令牌缺失或无效";

    private final AcquisitionProperties properties;

    public GatewayTokenVerifier(AcquisitionProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验请求头令牌。
     *
     * @param providedToken 请求头原值（可为 null）
     * @throws GatewayAuthException 未配置、缺失或不匹配
     */
    public void verify(String providedToken) {
        String expected = properties.gatewayToken();
        if (expected == null || expected.isBlank()) {
            // 不记录配置缺失细节，避免把"是否配置了密钥"暴露给调用方。
            throw new GatewayAuthException(REJECT_MESSAGE);
        }
        if (providedToken == null || providedToken.isBlank()) {
            throw new GatewayAuthException(REJECT_MESSAGE);
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = providedToken.getBytes(StandardCharsets.UTF_8);
        if (expectedBytes.length != providedBytes.length
                || !MessageDigest.isEqual(expectedBytes, providedBytes)) {
            throw new GatewayAuthException(REJECT_MESSAGE);
        }
    }
}
