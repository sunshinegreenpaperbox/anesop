package com.anesop.testing;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * SYNTHETIC 测试数据工厂（S0-11）。
 *
 * <p>所有标识强制以 {@value #SYNTHETIC_PREFIX} 开头，ID 由固定种子生成（可重复、无随机性），
 * 便于在日志/夹具/断言中一眼识别合成数据；<b>禁止</b>放入真实姓名、身份证、住院号、手机号。
 * 只生成值对象，不写库；写库仍由各集成测试的 SQL/Repository 完成。
 */
public final class TestDataFactory {

    /** 合成数据固定前缀（AGENTS.md：测试只用标记 SYNTHETIC 的合成数据）。 */
    public static final String SYNTHETIC_PREFIX = "SYNTH-";

    private static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9-]{1,32}");

    private TestDataFactory() {
    }

    /** 合成用户。 */
    public record SyntheticUser(String id, String username, String displayName) {
    }

    /** 合成患者。 */
    public record SyntheticPatient(String id, String patientNo, String displayName, LocalDate birthDate) {
    }

    /** 合成手术病例。 */
    public record SyntheticCase(
            String id, String caseNo, String patientId, String procedureName, OffsetDateTime scheduledAt) {
    }

    /**
     * 生成合成用户。
     *
     * @param key 稳定键（小写字母/数字/连字符），同一 key 永远得到同一 ID
     * @return 合成用户
     */
    public static SyntheticUser user(String key) {
        String normalized = normalize(key);
        return new SyntheticUser(
                syntheticUuid("user-" + normalized),
                SYNTHETIC_PREFIX + "user-" + normalized,
                "合成用户 " + normalized);
    }

    /**
     * 生成合成患者（固定出生日期，避免使用真实日期）。
     *
     * @param key 稳定键
     * @return 合成患者
     */
    public static SyntheticPatient patient(String key) {
        String normalized = normalize(key);
        return new SyntheticPatient(
                syntheticUuid("patient-" + normalized),
                SYNTHETIC_PREFIX + "P-" + normalized.toUpperCase(Locale.ROOT),
                "合成患者 " + normalized,
                LocalDate.of(1980, 1, 1));
    }

    /**
     * 生成合成病例（固定排程时间，避免依赖当前时钟）。
     *
     * @param key 稳定键
     * @param patient 所属合成患者
     * @return 合成病例
     */
    public static SyntheticCase anesthesiaCase(String key, SyntheticPatient patient) {
        String normalized = normalize(key);
        return new SyntheticCase(
                syntheticUuid("case-" + normalized),
                SYNTHETIC_PREFIX + "C-" + normalized.toUpperCase(Locale.ROOT),
                patient.id(),
                "合成术式 " + normalized,
                OffsetDateTime.of(2026, 1, 5, 8, 30, 0, 0, ZoneOffset.ofHours(8)));
    }

    /**
     * 判断标识是否带 SYNTHETIC 前缀。
     *
     * @param value 待判断值
     * @return 为 null 或不带前缀时返回 false
     */
    public static boolean isSynthetic(String value) {
        return value != null && value.startsWith(SYNTHETIC_PREFIX);
    }

    private static String normalize(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("合成数据 key 只允许小写字母/数字/连字符，长度 1–32");
        }
        return key;
    }

    private static String syntheticUuid(String seed) {
        return UUID.nameUUIDFromBytes((SYNTHETIC_PREFIX + seed).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
