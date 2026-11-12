package com.anesop.m0.api.security;

import java.util.Locale;

/**
 * 口令强度规则（S-3）：长度、字符类别、与旧口令/账号的关系。
 *
 * <p>规则与 auth.yaml 的 ChangePasswordRequest 描述一致：12–128 字符，至少包含大写、
 * 小写、数字、符号四类中的三类；不得与旧口令相同、不得包含登录账号。
 * 抛出的 {@link IllegalArgumentException} 只带固定安全文案（不含口令本身），
 * 由 Web 层映射为 400 PARAM_INVALID。
 */
public final class PasswordPolicy {

    /** 最小长度。 */
    public static final int MIN_LENGTH = 12;
    /** 最大长度（与契约 maxLength 一致）。 */
    public static final int MAX_LENGTH = 128;
    /** 至少满足的字符类别数。 */
    public static final int MIN_CHARACTER_CLASSES = 3;

    private PasswordPolicy() {
    }

    public static void validate(String newPassword, String username, String oldPassword) {
        if (newPassword == null || newPassword.length() < MIN_LENGTH) {
            throw new IllegalArgumentException("新口令长度需为 " + MIN_LENGTH + "–" + MAX_LENGTH + " 个字符");
        }
        if (newPassword.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("新口令长度需为 " + MIN_LENGTH + "–" + MAX_LENGTH + " 个字符");
        }
        if (newPassword.equals(oldPassword)) {
            throw new IllegalArgumentException("新口令不得与旧口令相同");
        }
        if (username != null && !username.isBlank()
                && newPassword.toLowerCase(Locale.ROOT).contains(username.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("新口令不得包含登录账号");
        }
        if (characterClasses(newPassword) < MIN_CHARACTER_CLASSES) {
            throw new IllegalArgumentException("新口令需至少包含大写字母、小写字母、数字、符号中的三类");
        }
    }

    static int characterClasses(String value) {
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        boolean symbol = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isUpperCase(c)) {
                upper = true;
            } else if (Character.isLowerCase(c)) {
                lower = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            } else {
                symbol = true;
            }
        }
        return (upper ? 1 : 0) + (lower ? 1 : 0) + (digit ? 1 : 0) + (symbol ? 1 : 0);
    }
}
