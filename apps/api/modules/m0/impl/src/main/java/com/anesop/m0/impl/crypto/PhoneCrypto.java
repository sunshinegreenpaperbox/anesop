package com.anesop.m0.impl.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.springframework.stereotype.Component;

/**
 * 手机号字段加解密（M2-M0-02，方案书 §3.9.3）：AES-256-GCM + 随机 12 字节 IV。
 *
 * <p>存储格式：{@code Base64(iv || ciphertext || tag)}，IV 12 字节、认证标签 128 位，
 * 单列 {@code sys_user.phone_enc}（varchar(512)）可容纳 11 位手机号密文。每条记录独立随机 IV，
 * 同一明文两次加密结果不同；GCM 认证标签使任何篡改（含改 IV/改密文）解密即失败。
 *
 * <p>密钥来自 {@link FieldCryptoKeyProvider}（{@code ANESOP_FIELD_KEY}），版本标识写入
 * {@code sys_user.phone_key_id}：解密时必须显式传入该 key_id 并与当前密钥版本比对，版本不匹配
 * 抛 {@link PhoneCryptoException}，避免用新密钥硬解旧密文而得到错误结果。轮换流程见
 * docs/modules/m0/README.md。
 *
 * <p>解密只允许在 M0 授权服务内部（用户管理、手机号精确查询后的展示脱敏）调用；
 * DTO、日志、审计、异常信息一律只出现脱敏值（如 {@code 138****5678}）。
 */
@Component
public class PhoneCrypto {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** 随机 IV 长度（字节）：GCM 推荐 96 位，避免重复 IV 风险。 */
    static final int IV_BYTES = 12;

    /** 认证标签长度（位）：128 位是 GCM 完整强度。 */
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final String keyId;
    private final SecureRandom secureRandom = new SecureRandom();

    public PhoneCrypto(FieldCryptoKeyProvider keyProvider) {
        // 只保留不可变引用（SpotBugs EI_EXPOSE_REP2）：密钥对象创建后不再变化。
        this.key = keyProvider.fieldKey();
        this.keyId = keyProvider.keyId();
    }

    /** 当前字段密钥版本标识；写入 {@code sys_user.phone_key_id}。 */
    public String keyId() {
        return keyId;
    }

    /**
     * 加密手机号明文。
     *
     * @param plain 已规范化的手机号明文（非空）；本方法不校验业务格式
     * @return {@code Base64(iv || ciphertext || tag)}
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            throw new IllegalArgumentException("待加密手机号不能为空");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(sealed, 0, combined, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            // 不含明文/密钥；GCM 参数由本类固定，失败说明运行环境异常。
            throw new PhoneCryptoException("手机号加密失败", e);
        }
    }

    /**
     * 解密手机号密文；调用方必须传入该行 {@code phone_key_id}。
     *
     * @throws PhoneCryptoException 密钥版本不匹配、密文被篡改或格式非法
     */
    public String decrypt(String ciphertext, String storedKeyId) {
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new PhoneCryptoException("手机号密文为空");
        }
        if (storedKeyId == null || !storedKeyId.equals(keyId)) {
            // 只比较版本标识，不回显两者内容；版本不匹配按“不可解密”处理（需按旧密钥单独迁移）。
            throw new PhoneCryptoException("手机号密文密钥版本不匹配");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            if (combined.length <= IV_BYTES) {
                throw new PhoneCryptoException("手机号密文格式非法");
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_BYTES);
            byte[] sealed = Arrays.copyOfRange(combined, IV_BYTES, combined.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new PhoneCryptoException("手机号密文格式非法", e);
        } catch (GeneralSecurityException e) {
            // AEADBadTagException 等：篡改或密钥错误，统一固定文案。
            throw new PhoneCryptoException("手机号密文校验失败", e);
        }
    }

    /**
     * 展示用脱敏：解密后按 {@link #mask(String)} 处理；无手机号返回 {@code null}。
     *
     * @throws PhoneCryptoException 密钥版本不匹配或密文损坏（由调用方决定降级策略）
     */
    public String masked(String ciphertext, String storedKeyId) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        return mask(decrypt(ciphertext, storedKeyId));
    }

    /**
     * 手机号脱敏：保留前 3 位与后 4 位，中间以 {@code *} 填充（如 {@code 138****5678}）。
     * 长度不足 8 位时整体以 {@code ****} 掩盖，避免短号码被反推。
     */
    public static String mask(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        int length = plain.length();
        if (length <= 7) {
            return "****";
        }
        int stars = length - 7;
        return plain.substring(0, 3) + "*".repeat(stars) + plain.substring(length - 4);
    }
}
