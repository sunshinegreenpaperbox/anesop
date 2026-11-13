package com.anesop.m2.impl.acquisition;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anesop.m2.api.acquisition.GatewayAuthException;
import com.anesop.m2.impl.config.AcquisitionProperties;
import org.junit.jupiter.api.Test;

/**
 * 网关令牌校验单测（M3-M8-01）：fail-closed、时序安全比较、空值分支。
 */
class GatewayTokenVerifierTest {

    private static final String TOKEN = "synth-gateway-token";

    @Test
    void acceptsExactMatchOnly() {
        GatewayTokenVerifier verifier = verifier(TOKEN);
        assertThatCode(() -> verifier.verify(TOKEN)).doesNotThrowAnyException();
    }

    @Test
    void rejectsDifferentContentWithSameLength() {
        GatewayTokenVerifier verifier = verifier(TOKEN);
        // 等长但内容不同：覆盖常量时间比较分支（不是长度分支）。
        assertThatThrownBy(() -> verifier.verify("synth-gateway-tokem"))
                .isInstanceOf(GatewayAuthException.class)
                .hasMessageNotContaining(TOKEN);
    }

    @Test
    void rejectsMissingBlankAndUnconfiguredToken() {
        GatewayTokenVerifier verifier = verifier(TOKEN);
        assertThatThrownBy(() -> verifier.verify(null)).isInstanceOf(GatewayAuthException.class);
        assertThatThrownBy(() -> verifier.verify("  ")).isInstanceOf(GatewayAuthException.class);
        assertThatThrownBy(() -> verifier.verify("short")).isInstanceOf(GatewayAuthException.class);

        GatewayTokenVerifier unconfigured = verifier("");
        assertThatThrownBy(() -> unconfigured.verify(TOKEN)).isInstanceOf(GatewayAuthException.class);
        GatewayTokenVerifier nullConfigured = verifier(null);
        assertThatThrownBy(() -> nullConfigured.verify(TOKEN)).isInstanceOf(GatewayAuthException.class);
    }

    private static GatewayTokenVerifier verifier(String token) {
        return new GatewayTokenVerifier(new AcquisitionProperties(token, 120L, 24L, 24L, true));
    }
}
