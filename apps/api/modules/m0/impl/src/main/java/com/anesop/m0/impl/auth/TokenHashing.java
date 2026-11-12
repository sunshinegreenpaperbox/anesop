package com.anesop.m0.impl.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 令牌哈希工具：刷新令牌只以 SHA-256 十六进制哈希入库（V3 的 {@code token_hash}）。
 *
 * <p>不使用 BCrypt：刷新令牌本身是 256 位随机值，无需慢哈希；SHA-256 便于按哈希等值查询。
 */
public final class TokenHashing {

    private TokenHashing() {
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，缺失说明运行环境被裁剪，必须立即失败。
            throw new IllegalStateException("JVM 不支持 SHA-256，无法处理刷新令牌", e);
        }
    }
}
