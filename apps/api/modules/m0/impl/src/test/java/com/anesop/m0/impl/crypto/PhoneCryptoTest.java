package com.anesop.m0.impl.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 手机号字段加解密（M2-M0-02，方案书 §3.9.3）：AES-256-GCM 往返、随机 IV、篡改拒绝、密钥版本校验与脱敏。
 */
class PhoneCryptoTest {

    private static final String PHONE = "13800005678";

    private static byte[] diverse(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) ((i * 53 + 7) % 251 + 1);
        }
        return bytes;
    }

    private static FieldCryptoKeyProvider provider(String keyId) {
        return new FieldCryptoKeyProvider(
                Base64.getEncoder().encodeToString(diverse(32)),
                keyId,
                Base64.getEncoder().encodeToString(diverse(48)),
                false);
    }

    private static PhoneCrypto crypto() {
        return new PhoneCrypto(provider("v1"));
    }

    @Test
    void encryptThenDecryptRoundTrips() {
        PhoneCrypto crypto = crypto();

        String encrypted = crypto.encrypt(PHONE);

        assertThat(encrypted).isNotBlank().doesNotContain(PHONE);
        assertThat(crypto.decrypt(encrypted, crypto.keyId())).isEqualTo(PHONE);
        // 存储格式 Base64(iv || ciphertext || tag)：iv 12 + tag 16 + 明文 11 = 39 字节。
        assertThat(Base64.getDecoder().decode(encrypted)).hasSize(PhoneCrypto.IV_BYTES + 16 + PHONE.length());
        System.out.printf("[M2-M0-02][crypto] AES-256-GCM 往返通过 cipherBytes=%d keyId=%s%n",
                Base64.getDecoder().decode(encrypted).length, crypto.keyId());
    }

    @Test
    void everyEncryptionUsesFreshIv() {
        PhoneCrypto crypto = crypto();

        String first = crypto.encrypt(PHONE);
        String second = crypto.encrypt(PHONE);

        assertThat(first).as("同一明文两次加密必须不同（随机 IV）").isNotEqualTo(second);
        assertThat(crypto.decrypt(first, crypto.keyId())).isEqualTo(PHONE);
        assertThat(crypto.decrypt(second, crypto.keyId())).isEqualTo(PHONE);
    }

    @Test
    void tamperedCiphertextIsRejected() {
        PhoneCrypto crypto = crypto();
        byte[] sealed = Base64.getDecoder().decode(crypto.encrypt(PHONE));
        // 篡改密文体一个字节：GCM 认证标签必须拒绝。
        sealed[sealed.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(sealed);

        assertThatThrownBy(() -> crypto.decrypt(tampered, crypto.keyId()))
                .isInstanceOf(PhoneCryptoException.class)
                .hasMessageContaining("校验失败")
                .hasMessageNotContaining(tampered);
    }

    @Test
    void wrongKeyVersionIsRejected() {
        PhoneCrypto crypto = crypto();
        String encrypted = crypto.encrypt(PHONE);

        assertThatThrownBy(() -> crypto.decrypt(encrypted, "v2"))
                .isInstanceOf(PhoneCryptoException.class)
                .hasMessageContaining("密钥版本不匹配");
        assertThatThrownBy(() -> crypto.decrypt(encrypted, null))
                .isInstanceOf(PhoneCryptoException.class)
                .hasMessageContaining("密钥版本不匹配");
    }

    @Test
    void sameKeyIdWithDifferentKeyIsRejected() {
        PhoneCrypto writer = crypto();
        byte[] otherKey = new byte[32];
        for (int i = 0; i < otherKey.length; i++) {
            otherKey[i] = (byte) ((i * 97 + 19) % 251 + 1);
        }
        PhoneCrypto other = new PhoneCrypto(new FieldCryptoKeyProvider(
                Base64.getEncoder().encodeToString(otherKey),
                "v1",
                Base64.getEncoder().encodeToString(diverse(48)),
                false));

        String encrypted = writer.encrypt(PHONE);
        // S-9：key_id 相同但密钥材料不同（例如误配/回滚错密钥）必须由 GCM 认证标签拒绝，
        // 不能把错误密钥解出的乱码当成手机号返回。
        assertThatThrownBy(() -> other.decrypt(encrypted, "v1"))
                .isInstanceOf(PhoneCryptoException.class)
                .hasMessageContaining("校验失败");
    }

    @Test
    void malformedCiphertextIsRejected() {
        PhoneCrypto crypto = crypto();

        assertThatThrownBy(() -> crypto.decrypt("", crypto.keyId()))
                .isInstanceOf(PhoneCryptoException.class)
                .hasMessageContaining("为空");
        assertThatThrownBy(() -> crypto.decrypt("not-base64-!!", crypto.keyId()))
                .isInstanceOf(PhoneCryptoException.class)
                .hasMessageContaining("格式非法");
        assertThatThrownBy(() -> crypto.decrypt(
                Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.UTF_8)), crypto.keyId()))
                .isInstanceOf(PhoneCryptoException.class)
                .hasMessageContaining("格式非法");
    }

    @Test
    void maskingKeepsPrefixAndSuffixOnly() {
        assertThat(PhoneCrypto.mask(PHONE)).isEqualTo("138****5678");
        assertThat(PhoneCrypto.mask("12345678")).isEqualTo("123*5678");
        assertThat(PhoneCrypto.mask("1234567")).isEqualTo("****");
        assertThat(PhoneCrypto.mask(null)).isNull();
        assertThat(PhoneCrypto.mask("")).isNull();
    }

    @Test
    void maskedReturnsNullWhenNoPhoneStored() {
        PhoneCrypto crypto = crypto();

        assertThat(crypto.masked(null, null)).isNull();
        assertThat(crypto.masked("  ", "v1")).isNull();
        assertThat(crypto.masked(crypto.encrypt(PHONE), crypto.keyId())).isEqualTo("138****5678");
    }

    @Test
    void encryptRejectsEmptyPlaintext() {
        PhoneCrypto crypto = crypto();

        assertThatThrownBy(() -> crypto.encrypt(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> crypto.encrypt(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
