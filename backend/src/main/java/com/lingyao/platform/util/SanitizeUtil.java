package com.lingyao.platform.util;

import org.springframework.web.util.HtmlUtils;

/**
 * 输入清洗工具 — Bug-06/07 修复
 * XSS 防御：转义 HTML 特殊字符
 */
public final class SanitizeUtil {

    private SanitizeUtil() {}

    /**
     * HTML 转义，防止 XSS
     * < > & " ' 全部转成 &lt; &gt; &amp; &quot; &#x27;
     */
    public static String escapeHtml(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return HtmlUtils.htmlEscape(input);
    }

    /**
     * 控制字符清洗（防止 ANSI 转义攻击）
     */
    public static String stripControlChars(String input) {
        if (input == null) return null;
        // 移除 \u0000-\u001F 控制字符和 \u007F DEL
        return input.replaceAll("[\\u0000-\\u001F\\u007F]", "");
    }
}
