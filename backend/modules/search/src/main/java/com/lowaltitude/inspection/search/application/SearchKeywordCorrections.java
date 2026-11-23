package com.lowaltitude.inspection.search.application;

import java.util.Map;
import java.util.Optional;

/**
 * 纠错（画板 SearchPre“你是不是要搜”）：一期只认平台词表里几个常见错字，整词相同才给建议，不做拼音和模糊匹配。
 * 词表以后由运营端维护时换掉这个实现即可。
 */
@FunctionalInterface
public interface SearchKeywordCorrections {

    Optional<String> correct(String keyword);

    /** 默认词表：巡检、机器狗、无人机、航拍、测绘、研学几个平台常用词的同音错字。 */
    Map<String, String> DEFAULT_WORDS = Map.of(
            "巡简", "巡检",
            "寻检", "巡检",
            "机器够", "机器狗",
            "无人鸡", "无人机",
            "航排", "航拍",
            "侧绘", "测绘",
            "测会", "测绘",
            "研雪", "研学");

    static SearchKeywordCorrections of(Map<String, String> words) {
        Map<String, String> normalized = words.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> SearchPorts.normalize(entry.getKey()),
                        Map.Entry::getValue));
        return keyword -> Optional.ofNullable(normalized.get(SearchPorts.normalize(keyword)));
    }

    static SearchKeywordCorrections defaults() {
        return of(DEFAULT_WORDS);
    }
}
