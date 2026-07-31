package com.adskip.app;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 跳过 / 排除正则集中管理。
 *
 * <p>所有正则只在 {@code AdSkipRegex} 定义，服务与（未来）测试共用，
 * 禁止在业务代码内联硬编码（设计文档 §七「正则集中管理」）。
 *
 * <p><b>跳过正则：</b>锚定按钮文案 {@code ^(\s*&lt;kw&gt;\s*|...)$}，
 * 避免误匹配「关闭会员」之类（设计 §六「防误触正则」）。
 *
 * <p><b>排除正则：</b>整窗子串扫描 {@code &lt;kw1&gt;|&lt;kw2&gt;|...}，命中则不点（防误触）。
 *
 * <p>关键词均视为<b>正则片段</b>（如「跳过\s*\d+\s*s?」），由调用方从 {@link AdSkipPrefs} 传入，
 * 拼接为完整正则。若拼接后非法，回退为「永不匹配」的安全正则，确保不崩溃、不误点。
 */
public final class AdSkipRegex {

    /** 安全兜底：永不整体匹配。 */
    private static final Pattern NEVER_MATCH = Pattern.compile("^(?!x)x$");
    /** 安全兜底：永不子串匹配。 */
    private static final Pattern NEVER_MATCH_SUB = Pattern.compile("(?s)(?!x)x");

    private final Pattern skipPattern;
    private final Pattern excludePattern;

    /**
     * 构造（编译当前配置下的跳过 / 排除正则）。
     *
     * @param skipKeywords   跳过按钮文案正则片段集合（来自 {@link AdSkipPrefs#getSkipKeywords()}）
     * @param excludeKeywords 防误触排除词正则片段集合（来自 {@link AdSkipPrefs#getExcludeKeywords()}）
     */
    public AdSkipRegex(Set<String> skipKeywords, Set<String> excludeKeywords) {
        this.skipPattern = compileSkip(skipKeywords);
        this.excludePattern = compileExclude(excludeKeywords);
    }

    /** 编译跳过正则：{@code ^(\s*(?:kw1)\s*|\s*(?:kw2)\s*|...) $}。 */
    public static Pattern compileSkip(Set<String> keywords) {
        StringBuilder sb = new StringBuilder("^(");
        boolean first = true;
        if (keywords != null) {
            for (String k : keywords) {
                if (k == null || k.isEmpty()) {
                    continue;
                }
                if (!first) {
                    sb.append("|");
                }
                sb.append("\\s*(?:").append(k).append(")\\s*");
                first = false;
            }
        }
        sb.append(")$");
        if (first) {
            return NEVER_MATCH;
        }
        try {
            return Pattern.compile(sb.toString());
        } catch (Exception e) {
            return NEVER_MATCH;
        }
    }

    /** 编译排除正则：{@code (?:kw1)|(?:kw2)|...}（子串匹配，无锚定）。 */
    public static Pattern compileExclude(Set<String> keywords) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        if (keywords != null) {
            for (String k : keywords) {
                if (k == null || k.isEmpty()) {
                    continue;
                }
                if (!first) {
                    sb.append("|");
                }
                sb.append("(?:").append(k).append(")");
                first = false;
            }
        }
        if (first) {
            return NEVER_MATCH_SUB;
        }
        try {
            return Pattern.compile(sb.toString());
        } catch (Exception e) {
            return NEVER_MATCH_SUB;
        }
    }

    /** 按钮文案是否命中跳过正则（锚定整体匹配）。 */
    public boolean matchesSkip(String text) {
        if (text == null) {
            return false;
        }
        return skipPattern.matcher(text).matches();
    }

    /** 整窗文本是否含排除词（子串查找）。 */
    public boolean containsExclude(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return excludePattern.matcher(text).find();
    }
}
