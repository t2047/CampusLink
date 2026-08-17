package com.app.campusagent.util;

import java.nio.charset.StandardCharsets;

/**
 * 密码规则共享校验（注册 / 管理员创建 / 修改密码 / 前端提示共用同一套约束）。
 *
 * <p>最小长度按字符数（6），最大长度按 UTF-8 字节数（72，BCrypt 的硬截断上限）。
 * 若只按字符数限制，中文、emoji 等多字节字符会绕过 72 字节上限，BCrypt 截断后
 * 不同密码可能等价（见 ChangePassWord.md）。</p>
 */
public final class PasswordRules {

    /** 最小密码长度（字符数，与既有注册约束一致）。 */
    public static final int MIN_LENGTH = 6;

    /** BCrypt 密码字节上限：超出 72 字节的部分被截断，导致不同密码等价。 */
    public static final int MAX_UTF8_BYTES = 72;

    private PasswordRules() {
    }

    /**
     * 校验密码长度：非空、至少 {@value #MIN_LENGTH} 个字符，且 UTF-8 编码不超过
     * {@value #MAX_UTF8_BYTES} 字节。空白密码由调用方（{@code @NotBlank} 或显式检查）处理。
     */
    public static boolean isValidLength(String password) {
        return password != null
                && password.length() >= MIN_LENGTH
                && utf8Bytes(password) <= MAX_UTF8_BYTES;
    }

    public static int utf8Bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
