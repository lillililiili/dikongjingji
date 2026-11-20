package com.lowaltitude.inspection.live.application;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 按词表过滤弹幕（开发用的小词表；生产换内容审核服务，审核由谁负责见概要设计 §14 D20 ④）。
 * 比较前去掉空白和常见的分隔符、统一小写，所以“加 微 信”“V.X”这样拆开写的也拦得住。
 */
public final class KeywordCommentFilter implements LivePorts.CommentFilter {

    private final List<String> words;

    public KeywordCommentFilter(List<String> words) {
        this.words = words == null ? List.of() : words.stream()
                .filter(Objects::nonNull)
                .map(KeywordCommentFilter::normalize)
                .filter(word -> !word.isEmpty())
                .distinct()
                .toList();
    }

    @Override
    public boolean blocks(String text) {
        if (text == null) {
            return false;
        }
        String normalized = normalize(text);
        return words.stream().anyMatch(normalized::contains);
    }

    List<String> words() {
        return words;
    }

    static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s.\\-_*·,，。、|/\\\\]+", "");
    }
}
