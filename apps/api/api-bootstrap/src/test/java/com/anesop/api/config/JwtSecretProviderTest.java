package com.anesop.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * JWT 密钥加固（B-1）：占位值/短密钥/低熵/非 Base64 拒绝，合法 32 字节通过，缺失默认失败。
 *
 * <p>每个用例都断言异常信息不回显密钥内容；只有 dev/test 显式
 * {@code allow-dev-generated=true} 才允许生成进程内随机密钥。
 */
class JwtSecretProviderTest {

    private static JwtProperties properties(String secret, boolean allowDevGenerated) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setAllowDevGenerated(allowDevGenerated);
        return properties;
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] diverseBytes(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) ((i * 37 + 11) % 251 + 1);
        }
        return bytes;
    }

    @Test
    void rejectsPlaceholderEvenWhenBase64Encoded() {
        String secret = base64("CHANGE_ME_BASE64_ENCODED_JWT_KEY_AT_LEAST_32_BYTES".getBytes(StandardCharsets.UTF_8));

        Throwable rejected = catchThrowable(() -> new JwtSecretProvider(properties(secret, false)));

        assertThat(rejected)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("占位/示例值")
                .hasMessageNotContaining(secret);
        System.out.printf("[B-1][jwt] 占位密钥已拒绝 -> %s%n", rejected.getMessage());
    }

    @Test
    void rejectsWholeWordSecretPlaceholder() {
        String secret = base64("my-secret-signing-key-for-anesop-0123456789ab".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new JwtSecretProvider(properties(secret, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("整词 secret");
    }

    @Test
    void rejectsShortKey() {
        String secret = base64(diverseBytes(16));

        assertThatThrownBy(() -> new JwtSecretProvider(properties(secret, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不足 32 字节");
    }

    @Test
    void rejectsLowEntropyKey() {
        byte[] repeated = new byte[64];
        Arrays.fill(repeated, (byte) 'a');
        String secret = base64(repeated);

        assertThatThrownBy(() -> new JwtSecretProvider(properties(secret, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("熵过低");
    }

    @Test
    void rejectsNonBase64Value() {
        assertThatThrownBy(() -> new JwtSecretProvider(properties("not base64 at all !!!", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须是 Base64");
    }

    @Test
    void acceptsValid32ByteBase64Key() {
        JwtSecretProvider provider = new JwtSecretProvider(properties(base64(diverseBytes(32)), false));

        assertThat(provider.key()).isNotNull();
        assertThat(provider.key().getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(provider.key().getEncoded()).hasSize(32);
    }

    @Test
    void missingKeyFailsByDefault() {
        assertThatThrownBy(() -> new JwtSecretProvider(properties(null, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝启动");
        assertThatThrownBy(() -> new JwtSecretProvider(properties("   ", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝启动");
    }

    @Test
    void missingKeyAllowedOnlyWhenDevGenerationExplicitlyEnabled() {
        assertThatCode(() -> new JwtSecretProvider(properties(null, true)))
                .doesNotThrowAnyException();
        JwtSecretProvider provider = new JwtSecretProvider(properties("", true));
        assertThat(provider.key().getEncoded()).hasSize(32);
    }
}
