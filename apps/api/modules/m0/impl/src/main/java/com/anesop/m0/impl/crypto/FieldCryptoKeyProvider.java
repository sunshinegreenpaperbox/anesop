package com.anesop.m0.impl.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 字段加密密钥提供者（M2-M0-02，方案书 §3.9.3 字段加密与脱敏）。
 *
 * <p>两类密钥，均为 Base64 编码、只从环境变量注入，不落库、不入日志：
 * <ul>
 *   <li>{@code anesop.security.field-key}（{@code ANESOP_FIELD_KEY}）：AES-256-GCM 字段密钥，
 *       解码后必须恰好 32 字节；用于手机号等敏感字段的加密；</li>
 *   <li>{@code anesop.security.phone-pepper}（{@code ANESOP_PHONE_PEPPER}）：HMAC-SHA-256
 *       pepper，解码后至少 32 字节；用于手机号精确检索/去重的不可逆哈希。</li>
 * </ul>
 *
 * <p>校验风格与 {@code JwtSecretProvider}（B-1）保持一致：拒绝占位/示例值（大小写不敏感
 * 包含 CHANGE_ME、CHANGEME、EXAMPLE、PLACEHOLDER、REPLACE_ME、TODO，或作为整词的 secret）、
 * 非 Base64 值、长度不足与低熵值（唯一字节数 &lt; 8）；错误信息只说明原因，绝不回显密钥内容。
 *
 * <p>缺失时的行为（fail-closed，不依赖 profile 判断，漏设 profile 也安全）：
 * <ul>
 *   <li>{@code anesop.security.crypto.allow-dev-generated=false}（默认/生产）：拒绝启动；</li>
 *   <li>仅 dev/test 显式置 true 时生成进程内随机密钥并 WARN：服务重启后旧密文无法解密，
 *       因此只允许用于本地演示与自动化测试。</li>
 * </ul>
 *
 * <p>{@code anesop.security.field-key-id} 是密钥版本标识（≤32 字符，入库到
 * {@code sys_user.phone_key_id}）。应用运行期只持有**一把**字段密钥：解密时 {@code key_id}
 * 与当前 {@code field-key-id} 不等即拒绝解密；轮换需 M1 交付的离线双密钥重加密工具，
 * 详见 docs/modules/m0/README.md。
 *
 * <p>S-8：字段密钥与 pepper 解码后**相等即拒绝启动**，否则一个密钥同时承担加密与检索哈希，
 * 破坏“泄露 pepper 不能解密密文”的隔离假设。
 */
@Component
public final class FieldCryptoKeyProvider {

    private static final Logger LOG = LoggerFactory.getLogger(FieldCryptoKeyProvider.class);

    /** AES-256 字段密钥长度（字节），必须恰好相等。 */
    public static final int FIELD_KEY_BYTES = 32;

    /** HMAC-SHA-256 pepper 最小长度（字节）。 */
    public static final int MIN_PEPPER_BYTES = 32;

    private static final int MIN_UNIQUE_BYTES = 8;
    private static final int MAX_KEY_ID_LENGTH = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 占位/示例值片段（大小写不敏感子串匹配），与 JwtSecretProvider 同一口径。 */
    private static final List<String> PLACEHOLDER_MARKERS = List.of(
            "CHANGE_ME", "CHANGEME", "EXAMPLE", "PLACEHOLDER", "REPLACE_ME", "TODO");

    private final SecretKey fieldKey;
    private final SecretKey pepperKey;
    private final String keyId;

    public FieldCryptoKeyProvider(
            @Value("${anesop.security.field-key:}") String fieldKey,
            @Value("${anesop.security.field-key-id:v1}") String fieldKeyId,
            @Value("${anesop.security.phone-pepper:}") String phonePepper,
            @Value("${anesop.security.crypto.allow-dev-generated:false}") boolean allowDevGenerated) {
        this.fieldKey = new SecretKeySpec(keyBytes(
                fieldKey, "anesop.security.field-key", "ANESOP_FIELD_KEY",
                FIELD_KEY_BYTES, true, allowDevGenerated), "AES");
        this.pepperKey = new SecretKeySpec(keyBytes(
                phonePepper, "anesop.security.phone-pepper", "ANESOP_PHONE_PEPPER",
                MIN_PEPPER_BYTES, false, allowDevGenerated), "HmacSHA256");
        this.keyId = validateKeyId(fieldKeyId);
        if (MessageDigest.isEqual(this.fieldKey.getEncoded(), this.pepperKey.getEncoded())) {
            throw new IllegalStateException(
                    "anesop.security.field-key 与 anesop.security.phone-pepper 解码后相同："
                            + "字段加密密钥与检索 pepper 必须分离（泄露 pepper 不得能解密 phone_enc），拒绝启动");
        }
    }

    /** AES-256-GCM 字段密钥（只读）；调用方不得打印或序列化。 */
    public SecretKey fieldKey() {
        return fieldKey;
    }

    /** 手机号 HMAC-SHA-256 pepper（只读）；调用方不得打印或序列化。 */
    public SecretKey pepperKey() {
        return pepperKey;
    }

    /** 字段密钥版本标识（入库到 {@code sys_user.phone_key_id}）。 */
    public String keyId() {
        return keyId;
    }

    private static String validateKeyId(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > MAX_KEY_ID_LENGTH
                || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalStateException(
                    "anesop.security.field-key-id 必须是 1–" + MAX_KEY_ID_LENGTH
                            + " 位字母/数字/._-（如 v1），拒绝启动");
        }
        return value;
    }

    /**
     * 解码并校验一类密钥。
     *
     * @param configured       配置原文（Base64，可空）
     * @param property         配置键名（错误信息使用）
     * @param environmentName  对应环境变量名（错误信息使用）
     * @param minBytes         允许的最小字节数
     * @param exactLength      true=必须恰好 minBytes（AES-256），false=至少 minBytes
     * @param allowDevGenerated 仅 dev/test 允许生成进程内随机密钥
     */
    private static byte[] keyBytes(String configured,
            String property,
            String environmentName,
            int minBytes,
            boolean exactLength,
            boolean allowDevGenerated) {
        String candidate = configured == null ? "" : configured.trim();
        if (candidate.isEmpty()) {
            if (!allowDevGenerated) {
                throw new IllegalStateException(
                        "未配置 " + property + "（环境变量 " + environmentName + "）："
                                + "默认拒绝启动；仅 dev/test 显式允许时生成进程内随机密钥");
            }
            byte[] random = new byte[minBytes];
            RANDOM.nextBytes(random);
            LOG.warn("未配置 {}（{}），已生成进程内随机密钥（仅 dev/test；重启后旧密文无法解密）",
                    property, environmentName);
            return random;
        }

        rejectPlaceholder(property, candidate);
        String generationHint = exactLength ? "openssl rand -base64 32" : "openssl rand -base64 48";
        byte[] decoded = decodeBase64(property, environmentName, candidate, generationHint);
        // 占位值也可能被 Base64 编码后提交，需对解码内容再检查一次（1:1 字节映射）。
        rejectPlaceholder(property, new String(decoded, StandardCharsets.ISO_8859_1));
        if (exactLength ? decoded.length != minBytes : decoded.length < minBytes) {
            throw new IllegalStateException(property + (exactLength ? " 解码后必须恰好 " : " 解码后不足 ")
                    + minBytes + " 字节，拒绝启动（生成方式：" + generationHint + "）");
        }
        if (uniqueBytes(decoded) < MIN_UNIQUE_BYTES) {
            throw new IllegalStateException(
                    property + " 熵过低（唯一字节数 < " + MIN_UNIQUE_BYTES + "），拒绝启动");
        }
        return decoded;
    }

    private static void rejectPlaceholder(String property, String material) {
        String upper = material.toUpperCase(Locale.ROOT);
        for (String marker : PLACEHOLDER_MARKERS) {
            if (upper.contains(marker)) {
                throw new IllegalStateException(property + " 是占位/示例值（含 " + marker + "），拒绝启动");
            }
        }
        if (containsSecretWord(material)) {
            throw new IllegalStateException(property + " 是占位/示例值（含整词 secret），拒绝启动");
        }
    }

    /** 检查 "secret" 作为独立整词出现（大小写不敏感），与 JwtSecretProvider 一致。 */
    private static boolean containsSecretWord(String material) {
        String lower = material.toLowerCase(Locale.ROOT);
        int index = lower.indexOf("secret");
        while (index >= 0) {
            boolean leftBoundary = index == 0 || !isWordChar(lower.charAt(index - 1));
            int end = index + "secret".length();
            boolean rightBoundary = end >= lower.length() || !isWordChar(lower.charAt(end));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            index = lower.indexOf("secret", index + 1);
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** 严格 Base64 解码；非 Base64 直接拒绝（不接受 UTF-8 明文密钥）。 */
    private static byte[] decodeBase64(String property, String environmentName, String material,
            String generationHint) {
        try {
            return Base64.getDecoder().decode(material);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    property + " 必须是 Base64 编码（环境变量 " + environmentName
                            + "；生成方式：" + generationHint + "），拒绝启动");
        }
    }

    private static int uniqueBytes(byte[] value) {
        Set<Byte> unique = new HashSet<>();
        for (byte b : value) {
            unique.add(b);
        }
        return unique.size();
    }
}
