package com.anesop.m0.impl.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 手机号检索哈希（M2-M0-02）：HMAC-SHA-256 确定性、带 pepper、规范化后同一号码命中同一哈希。
 */
class PhoneHasherTest {

    private static byte[] diverse(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) ((i * 67 + 13) % 251 + 1);
        }
        return bytes;
    }

    private static PhoneHasher hasher(String pepperSeed) {
        byte[] pepper = pepperSeed == null ? diverse(48) : pepperSeed.getBytes();
        return new PhoneHasher(new FieldCryptoKeyProvider(
                Base64.getEncoder().encodeToString(diverse(32)),
                "v1",
                Base64.getEncoder().encodeToString(pepper),
                false));
    }

    @Test
    void hashIsDeterministicAndHexEncoded() {
        PhoneHasher hasher = hasher(null);

        String first = hasher.hash("13800005678");
        String second = hasher.hash("13800005678");

        assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(first).as("不得出现明文").doesNotContain("13800005678");
        System.out.printf("[M2-M0-02][hash] HMAC-SHA-256 确定性通过 hash=%s…%n", first.substring(0, 16));
    }

    @Test
    void differentPepperProducesDifferentHash() {
        String withFirstPepper = hasher(null).hash("13800005678");
        String withSecondPepper = hasher("another-synthetic-pepper-material-1234567890").hash("13800005678");

        assertThat(withFirstPepper).isNotEqualTo(withSecondPepper);
    }

    @Test
    void normalizedFormsShareTheSameHash() {
        PhoneHasher hasher = hasher(null);
        String canonical = hasher.hash(PhoneHasher.normalize("13800005678"));

        assertThat(PhoneHasher.normalize("+86 138-0000-5678")).isEqualTo("13800005678");
        assertThat(PhoneHasher.normalize("0086 138 0000 5678")).isEqualTo("13800005678");
        assertThat(PhoneHasher.normalize("(138)0000-5678")).isEqualTo("13800005678");
        assertThat(PhoneHasher.normalize(null)).isEmpty();
        assertThat(hasher.hash(PhoneHasher.normalize("+86 138 0000 5678"))).isEqualTo(canonical);
    }

    @Test
    void differentPhonesHashDifferently() {
        PhoneHasher hasher = hasher(null);

        assertThat(hasher.hash("13800005678")).isNotEqualTo(hasher.hash("13900001234"));
    }

    @Test
    void emptyPhoneIsRejected() {
        PhoneHasher hasher = hasher(null);

        assertThatThrownBy(() -> hasher.hash(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> hasher.hash(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
