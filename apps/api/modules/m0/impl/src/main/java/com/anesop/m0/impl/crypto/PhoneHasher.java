package com.anesop.m0.impl.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * 手机号精确检索哈希（M2-M0-02）：HMAC-SHA-256 + pepper，输出小写十六进制。
 *
 * <p>用途：{@code sys_user.phone_hash} 等值查询与去重。手机号取值空间很小（11 位、约 10^10），
 * 不加密的 SHA-256 可被彩虹表反推，因此必须使用带密钥的 HMAC；pepper 来自
 * {@code anesop.security.phone-pepper}（{@code ANESOP_PHONE_PEPPER}，≥32 字节），
 * 与字段加密密钥分离：泄露 pepper 只能验证猜测的手机号，不能解密 {@code phone_enc}。
 *
 * <p>哈希不可逆、写入后不参与展示；返回 DTO 只给 {@code phoneMasked}，永不出 hash。
 */
@Component
public class PhoneHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKey pepper;

    public PhoneHasher(FieldCryptoKeyProvider keyProvider) {
        this.pepper = keyProvider.pepperKey();
    }

    /**
     * 计算手机号 HMAC-SHA-256（小写十六进制，64 字符，写入 varchar(128) 列）。
     *
     * @param phone 已规范化的手机号明文；调用方负责规范化为统一格式
     */
    public String hash(String phone) {
        if (phone == null || phone.isEmpty()) {
            throw new IllegalArgumentException("待哈希手机号不能为空");
        }
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(pepper);
            byte[] digest = mac.doFinal(phone.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM 不支持 HMAC-SHA-256，无法计算手机号哈希", e);
        }
    }

    /**
     * 规范化手机号：去掉空格、短横线、括号与 {@code +86}/{@code 86} 前缀。
     *
     * <p>规范化的唯一目的是让同一号码的不同书写形式命中同一个 hash；非中国内地手机号
     * 不做猜测性转换，由格式校验拒绝。
     *
     * @return 规范化结果；输入为空白时返回空字符串
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim()
                .replace(" ", "")
                .replace("-", "")
                .replace("(", "")
                .replace(")", "");
        if (value.startsWith("+86")) {
            value = value.substring(3);
        } else if (value.startsWith("0086")) {
            value = value.substring(4);
        }
        return value;
    }
}
