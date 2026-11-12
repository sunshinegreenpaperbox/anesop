package com.anesop.api.config;

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
import org.springframework.stereotype.Component;

/**
 * JWT HS256 密钥提供者（B-1 加固）。
 *
 * <p>规则：
 * <ul>
 *   <li>密钥必须是 Base64 编码，解码后 >= 32 字节（HS256 强度）；</li>
 *   <li>拒绝占位/示例值（大小写不敏感包含 CHANGE_ME、CHANGEME、EXAMPLE、PLACEHOLDER、
 *       REPLACE_ME、TODO，或作为整词的 secret）；</li>
 *   <li>拒绝低熵密钥（全同字符或唯一字符数 &lt; 8）；</li>
 *   <li>未配置时：{@code allow-dev-generated=true}（仅 dev/test）生成进程内随机密钥，
 *       否则拒绝启动——不依赖 profile 判断，漏设 profile 也是安全默认；</li>
 *   <li>错误信息只说明原因，绝不回显密钥内容。</li>
 * </ul>
 * 密钥不得写入仓库、日志或审计。RS256/CA/SSO 为外部依赖（BLOCKED-EXTERNAL EXT-05）。
 */
@Component
public final class JwtSecretProvider {

    private static final Logger LOG = LoggerFactory.getLogger(JwtSecretProvider.class);
    private static final int MIN_KEY_BYTES = 32;
    private static final int MIN_UNIQUE_CHARS = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 占位/示例值片段（大小写不敏感子串匹配）。 */
    private static final List<String> PLACEHOLDER_MARKERS = List.of(
            "CHANGE_ME", "CHANGEME", "EXAMPLE", "PLACEHOLDER", "REPLACE_ME", "TODO");

    private final SecretKey key;

    public JwtSecretProvider(JwtProperties properties) {
        String configured = properties.getSecret();
        if (configured == null || configured.isBlank()) {
            if (!properties.isAllowDevGenerated()) {
                throw new IllegalStateException(
                        "未配置 anesop.security.jwt.secret（环境变量 ANESOP_JWT_SECRET）："
                                + "默认拒绝启动；仅 dev/test 显式允许时生成进程内随机密钥");
            }
            byte[] random = new byte[MIN_KEY_BYTES];
            RANDOM.nextBytes(random);
            this.key = new SecretKeySpec(random, "HmacSHA256");
            LOG.warn("未配置 anesop.security.jwt.secret，已生成进程内随机 HS256 密钥"
                    + "（仅 dev/test；重启后旧令牌失效）");
            return;
        }

        String candidate = configured.trim();
        rejectPlaceholder(candidate);
        byte[] keyBytes = decodeBase64(candidate);
        // 占位值也可能被 Base64 编码后提交，需对解码内容再检查一次（1:1 字节映射，避免字符集替换）。
        rejectPlaceholder(new String(keyBytes, java.nio.charset.StandardCharsets.ISO_8859_1));
        if (keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "anesop.security.jwt.secret 解码后不足 " + MIN_KEY_BYTES + " 字节（HS256 强度要求），拒绝启动");
        }
        if (uniqueChars(candidate) < MIN_UNIQUE_CHARS || uniqueBytes(keyBytes) < MIN_UNIQUE_CHARS) {
            throw new IllegalStateException(
                    "anesop.security.jwt.secret 熵过低（唯一字符数 < " + MIN_UNIQUE_CHARS + "），拒绝启动");
        }
        this.key = new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    public SecretKey key() {
        return key;
    }

    private static void rejectPlaceholder(String secret) {
        String upper = secret.toUpperCase(Locale.ROOT);
        for (String marker : PLACEHOLDER_MARKERS) {
            if (upper.contains(marker)) {
                throw new IllegalStateException(
                        "anesop.security.jwt.secret 是占位/示例值（含 " + marker + "），拒绝启动");
            }
        }
        if (containsSecretWord(secret)) {
            throw new IllegalStateException(
                    "anesop.security.jwt.secret 是占位/示例值（含整词 secret），拒绝启动");
        }
    }

    /** 检查 "secret" 作为独立整词出现（大小写不敏感）：如 "my-secret-key" 或 "SECRET"。 */
    private static boolean containsSecretWord(String secret) {
        String lower = secret.toLowerCase(Locale.ROOT);
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

    /** 严格 Base64 解码；非 Base64 直接拒绝（不再接受 UTF-8 明文密钥）。 */
    private static byte[] decodeBase64(String secret) {
        try {
            return Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "anesop.security.jwt.secret 必须是 Base64 编码（生成方式：openssl rand -base64 48），拒绝启动");
        }
    }

    private static int uniqueChars(String value) {
        Set<Character> unique = new HashSet<>();
        for (int i = 0; i < value.length(); i++) {
            unique.add(value.charAt(i));
        }
        return unique.size();
    }

    private static int uniqueBytes(byte[] value) {
        Set<Byte> unique = new HashSet<>();
        for (byte b : value) {
            unique.add(b);
        }
        return unique.size();
    }
}
