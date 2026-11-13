package com.anesop.m1.impl.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 住院号匹配哈希（V7）：HMAC-SHA-256，pepper 由环境变量注入。
 *
 * <p>用途：{@code m1_sched.patient_snapshot.inpatient_no_hash} 的唯一索引与跨申请匹配。
 * 住院号明文不落库、不出接口；哈希不可逆。
 *
 * <p>pepper 来自 {@code anesop.m1.inpatient-pepper.value}（{@code ANESOP_INPATIENT_PEPPER}），
 * 必须 Base64 编码且解码后至少 32 字节。缺失、非 Base64 或长度不足时：
 * <ul>
 *   <li>{@code anesop.m1.inpatient-pepper.allow-dev-generated=false}（默认/生产）：拒绝启动（fail-closed）；</li>
 *   <li>仅 dev/test 显式置 true 时生成进程内随机 pepper 并 WARN：重启后旧哈希无法再做一致性匹配，
 *       只允许用于 SYNTHETIC 演示/自动化测试。</li>
 * </ul>
 * 生成方式：{@code openssl rand -base64 48}。
 *
 * <p>类声明为 {@code final}：构造器可能抛异常（pepper 校验），禁止子类化以消除
 * finalizer 攻击面（SpotBugs CT_CONSTRUCTOR_THROW），不改变运行时语义。
 */
@Component
public final class InpatientNoHasher {

    private static final Logger LOG = LoggerFactory.getLogger(InpatientNoHasher.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MIN_PEPPER_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey pepper;

    public InpatientNoHasher(
            @Value("${anesop.m1.inpatient-pepper.value:}") String pepper,
            @Value("${anesop.m1.inpatient-pepper.allow-dev-generated:false}") boolean allowDevGenerated) {
        byte[] pepperBytes = resolvePepper(pepper, allowDevGenerated);
        this.pepper = new SecretKeySpec(pepperBytes, HMAC_ALGORITHM);
    }

    /**
     * 计算住院号哈希（小写十六进制，64 字符）。
     *
     * @param inpatientNo 住院号明文（仅内存使用）
     * @throws IllegalArgumentException 输入为空
     */
    public String hash(String inpatientNo) {
        if (inpatientNo == null || inpatientNo.isBlank()) {
            throw new IllegalArgumentException("待哈希住院号不能为空");
        }
        byte[] input = inpatientNo.trim().getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(pepper);
            return HexFormat.of().formatHex(mac.doFinal(input));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM 不支持住院号哈希算法", e);
        }
    }

    private static byte[] resolvePepper(String configured, boolean allowDevGenerated) {
        String candidate = configured == null ? "" : configured.trim();
        if (candidate.isEmpty()) {
            if (!allowDevGenerated) {
                throw new IllegalStateException(
                        "未配置 anesop.m1.inpatient-pepper.value（环境变量 ANESOP_INPATIENT_PEPPER）："
                                + "默认拒绝启动；仅 dev/test 显式允许时生成进程内随机 pepper。"
                                + "生成方式：openssl rand -base64 48");
            }
            byte[] random = new byte[48];
            RANDOM.nextBytes(random);
            LOG.warn("未配置 anesop.m1.inpatient-pepper.value（ANESOP_INPATIENT_PEPPER），"
                    + "已生成进程内随机 pepper（仅 dev/test；重启后旧住院号哈希不可复用）");
            return random;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(candidate);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "anesop.m1.inpatient-pepper.value 不是合法 Base64，拒绝启动（生成方式：openssl rand -base64 48）");
        }
        if (decoded.length < MIN_PEPPER_BYTES) {
            throw new IllegalStateException("anesop.m1.inpatient-pepper.value 解码后不足 "
                    + MIN_PEPPER_BYTES + " 字节，拒绝启动（生成方式：openssl rand -base64 48）");
        }
        return decoded;
    }
}
