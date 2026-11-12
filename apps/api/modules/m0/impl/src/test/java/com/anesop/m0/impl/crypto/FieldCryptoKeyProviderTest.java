package com.anesop.m0.impl.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 字段加密密钥校验（M2-M0-02）：长度/占位值/低熵/非 Base64/缺失 fail-closed 与 dev 自动生成。
 *
 * <p>要点：与 JwtSecretProvider（B-1）同一口径——异常信息只说明原因、不回显密钥内容；
 * 生产（allow-dev-generated=false）缺失即拒绝启动，只有 dev/test 显式允许时才生成随机密钥。
 */
class FieldCryptoKeyProviderTest {

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] diverse(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) ((i * 37 + 11) % 251 + 1);
        }
        return bytes;
    }

    private static String fieldKey() {
        return base64(diverse(32));
    }

    private static String pepper() {
        return base64(diverse(48));
    }

    private static FieldCryptoKeyProvider provider(String fieldKey, String keyId, String pepper,
            boolean allowDevGenerated) {
        return new FieldCryptoKeyProvider(fieldKey, keyId, pepper, allowDevGenerated);
    }

    @Test
    void acceptsValidAes256KeyAndPepper() {
        String configuredKey = fieldKey();
        FieldCryptoKeyProvider provider = provider(configuredKey, "v1", pepper(), false);

        assertThat(provider.fieldKey().getAlgorithm()).isEqualTo("AES");
        assertThat(provider.fieldKey().getEncoded()).hasSize(FieldCryptoKeyProvider.FIELD_KEY_BYTES);
        assertThat(provider.pepperKey().getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(provider.keyId()).isEqualTo("v1");
        System.out.printf("[M2-M0-02][crypto] 密钥校验通过 keyId=%s keyBytes=%d%n",
                provider.keyId(), provider.fieldKey().getEncoded().length);
    }

    @Test
    void missingFieldKeyFailsStartupWhenDevGenerationDisabled() {
        assertThatThrownBy(() -> provider("", "v1", pepper(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANESOP_FIELD_KEY")
                .hasMessageContaining("拒绝启动");
    }

    @Test
    void missingPepperFailsStartupWhenDevGenerationDisabled() {
        assertThatThrownBy(() -> provider(fieldKey(), "v1", "", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANESOP_PHONE_PEPPER")
                .hasMessageContaining("拒绝启动");
    }

    @Test
    void devProfileGeneratesRandomKeysWhenMissing() {
        FieldCryptoKeyProvider first = provider("", "v1", "", true);
        FieldCryptoKeyProvider second = provider("", "v1", "", true);

        assertThat(first.fieldKey().getEncoded()).hasSize(FieldCryptoKeyProvider.FIELD_KEY_BYTES);
        assertThat(first.pepperKey().getEncoded())
                .hasSizeGreaterThanOrEqualTo(FieldCryptoKeyProvider.MIN_PEPPER_BYTES);
        assertThat(first.fieldKey().getEncoded())
                .as("每次启动生成不同密钥（进程内随机）")
                .isNotEqualTo(second.fieldKey().getEncoded());
    }

    @Test
    void rejectsPlaceholderMaterialEvenWhenBase64Encoded() {
        String placeholder = base64("CHANGE_ME_FIELD_KEY_FOR_PHONE_ENCRYPTION_32B".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> provider(placeholder, "v1", pepper(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("占位/示例值")
                .hasMessageNotContaining(placeholder);
    }

    @Test
    void rejectsWholeWordSecretPlaceholder() {
        String material = base64("my-secret-field-key-for-anesop-0123456789abc".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> provider(fieldKey(), "v1", material, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("整词 secret");
    }

    @Test
    void rejectsFieldKeyWithWrongLengthAndShowsFieldKeyCommand() {
        assertThatThrownBy(() -> provider(base64(diverse(16)), "v1", pepper(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("恰好 32 字节")
                .hasMessageContaining("openssl rand -base64 32");
    }

    @Test
    void rejectsSameMaterialForFieldKeyAndPepper() {
        String shared = base64(diverse(32));

        assertThatThrownBy(() -> provider(shared, "v1", shared, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须分离")
                .hasMessageContaining("拒绝启动");
    }

    @Test
    void rejectsShortPepperAndShowsPepperCommand() {
        assertThatThrownBy(() -> provider(fieldKey(), "v1", base64(diverse(16)), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不足 32 字节")
                .hasMessageContaining("openssl rand -base64 48");
    }

    @Test
    void rejectsNonBase64Material() {
        assertThatThrownBy(() -> provider("not-base64-!!", "v1", pepper(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    void rejectsLowEntropyMaterial() {
        byte[] repeated = new byte[32];
        Arrays.fill(repeated, (byte) 'a');

        assertThatThrownBy(() -> provider(base64(repeated), "v1", pepper(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("熵过低");
    }

    @Test
    void rejectsInvalidKeyId() {
        assertThatThrownBy(() -> provider(fieldKey(), "", pepper(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("field-key-id");
        assertThatThrownBy(() -> provider(fieldKey(), "v1 with space", pepper(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("field-key-id");
    }
}
