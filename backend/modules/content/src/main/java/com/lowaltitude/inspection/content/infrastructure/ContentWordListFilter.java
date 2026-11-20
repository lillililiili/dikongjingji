package com.lowaltitude.inspection.content.infrastructure;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.content.application.ContentTextFilter;

/**
 * 开发用的小词表过滤：引流、违法交易一类的词，以及写在评论里的手机号（评论不交换联系方式）。
 * 比较前去掉空白、横线和点，转小写，防止“加 微 信”这样拆开写。正式的过滤服务接入后替换掉它。
 */
public final class ContentWordListFilter implements ContentTextFilter {

    static final List<String> WORDS = List.of(
            "加微信", "加vx", "加v信", "私聊加", "代开发票", "刷单", "博彩", "赌博", "办证", "套现", "色情", "枪支");

    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-_.·]+");
    private static final Pattern MOBILE = Pattern.compile("1[3-9]\\d{9}");

    @Override
    public boolean blocks(String text) {
        if (text == null) {
            return false;
        }
        String compact = SEPARATORS.matcher(text).replaceAll("").toLowerCase(Locale.ROOT);
        return MOBILE.matcher(compact).find() || WORDS.stream().anyMatch(compact::contains);
    }
}
