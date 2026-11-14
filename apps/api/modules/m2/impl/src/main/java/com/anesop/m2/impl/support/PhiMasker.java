package com.anesop.m2.impl.support;

import java.util.regex.Pattern;

/**
 * 随访自由文本脱敏（M3-M11-01）。
 *
 * <p>患者反馈、并发症说明、镇痛调整说明与药物名称属自由文本，录入人可能误填手机号/
 * 身份证号等直接标识。落库与写审计前统一遮蔽：
 * <ul>
 *   <li>中国大陆手机号：容忍 {@code +86}/{@code 0086} 前缀、空格、短横线与括号，按 11 位
 *       数字识别并整段遮蔽，如 {@code +86 138-0013-8000 → 138****8000}；</li>
 *   <li>18 位居民身份证号：容忍空格/短横线，末位可为 {@code X/x}，保留前 6 位与后 4 位，
 *       中间 8 位遮蔽，如 {@code 110101 19900307 4567 → 110101********4567}；</li>
 *   <li>15 位旧版身份证号：容忍空格/短横线，保留前 6 位与后 3 位，中间 6 位遮蔽。</li>
 * </ul>
 *
 * <p>识别顺序为 18 位身份证 → 15 位身份证 → 手机号：先遮蔽身份证避免手机号规则吃掉其
 * 片段；15 位身份证规则显式排除 {@code 0086}+11 位的手机号写法（0086 不可能是旧版身份证
 * 地区码），保证 {@code 008613800138000} 按手机号遮蔽。匹配只替换形似标识的片段，其余
 * 原文（含日期、剂量、订单号等普通数字）保持不变。
 *
 * <p>**局限**：无法识别姓名、住院号（无统一格式）等无固定模式的标识，前端必须提示
 * “不得录入姓名/住院号/身份证号/手机号”，是否接入院内 DLP/姓名识别待确认
 * （docs/modules/m2/README.md §16.9 Q-10）。本类为纯函数，无状态，可安全用于并发调用；
 * 日志与审计不得输出原文。
 */
public final class PhiMasker {

    /** 数字之间允许的分隔符：空白、短横线、半角/全角括号。 */
    private static final String SEP = "[\\s\\-()（）]";

    /** 18 位身份证：17 位数字 + 末位数字或 X（可含分隔符）。 */
    private static final Pattern ID_CARD_18 = Pattern.compile(
            "(?<![0-9])[0-9](?:" + SEP + "*[0-9]){16}[0-9Xx](?![0-9A-Za-z])");

    /** 15 位旧版身份证：全部为数字（可含分隔符）。 */
    private static final Pattern ID_CARD_15 = Pattern.compile(
            "(?<![0-9])(?!0086" + SEP + "*[0-9])[0-9](?:" + SEP + "*[0-9]){14}"
                    + "(?![0-9A-Za-z])");

    /**
     * 手机号：可选 {@code +86}/{@code 0086} 前缀（可被括号包裹），11 位数字之间允许分隔符；
     * 前置可选左括号用于整段遮蔽 {@code (138) 0013-8000} 这类写法。
     */
    private static final Pattern MOBILE = Pattern.compile(
            "(?<![0-9])"
                    + "(?:[（(]\\s*)?"
                    + "(?:(?:[（(]\\s*(?:(?:\\+|00)?86)\\s*[）)]|(?:(?:\\+|00)?86))"
                    + SEP + "*)?"
                    + "1[3-9][0-9](?:" + SEP + "*[0-9]){8}"
                    + "(?![0-9])");

    private PhiMasker() {
    }

    /**
     * 对自由文本做脱敏；null/空白原样返回 null。
     *
     * @param text 原始文本（不得写入日志）
     * @return 脱敏后的文本（可直接落库/回显）
     */
    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String masked = ID_CARD_18.matcher(trimmed)
                .replaceAll(match -> maskIdCard18(match.group()));
        masked = ID_CARD_15.matcher(masked).replaceAll(match -> maskIdCard15(match.group()));
        return MOBILE.matcher(masked).replaceAll(match -> maskMobile(match.group()));
    }

    /** 仅用于自检/测试：文本中是否仍含可识别手机号/身份证号形似串。 */
    public static boolean containsMaskableIdentifier(String text) {
        if (text == null) {
            return false;
        }
        return ID_CARD_18.matcher(text).find() || ID_CARD_15.matcher(text).find()
                || MOBILE.matcher(text).find();
    }

    /** 18 位身份证：保留前 6 位与后 4 位（含末位 X）。 */
    private static String maskIdCard18(String matched) {
        String digits = compact(matched);
        return digits.substring(0, 6) + "********" + digits.substring(14);
    }

    /** 15 位旧版身份证：保留前 6 位与后 3 位。 */
    private static String maskIdCard15(String matched) {
        String digits = compact(matched);
        return digits.substring(0, 6) + "******" + digits.substring(12);
    }

    /** 手机号：去掉国家码与分隔符后保留前 3 位与后 4 位。 */
    private static String maskMobile(String matched) {
        String digits = compact(matched);
        if (digits.length() == 15 && digits.startsWith("0086")) {
            digits = digits.substring(4);
        } else if (digits.length() == 13 && digits.startsWith("86")) {
            digits = digits.substring(2);
        }
        return digits.substring(0, 3) + "****" + digits.substring(7);
    }

    /** 去掉分隔符，只保留数字与 X（身份证末位）。 */
    private static String compact(String matched) {
        return matched.replaceAll("[^0-9Xx]", "");
    }
}
