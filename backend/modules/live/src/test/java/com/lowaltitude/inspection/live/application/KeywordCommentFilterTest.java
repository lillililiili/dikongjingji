package com.lowaltitude.inspection.live.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 弹幕的关键词过滤（开发用的小词表）。启动配置里的默认词表在 LivePortsConfigurationTest 里检查。 */
class KeywordCommentFilterTest {

    private final KeywordCommentFilter filter = new KeywordCommentFilter(List.of("加微信", "加VX", " ", "刷单"));

    @Test
    void blocksWordsEvenWhenSplitBySpacesOrDotsAndIgnoresCase() {
        assertThat(filter.blocks("想要资料加微信")).isTrue();
        assertThat(filter.blocks("加 微 信 聊")).isTrue();
        assertThat(filter.blocks("加v.x私聊")).isTrue();
        assertThat(filter.blocks("有人刷*单吗")).isTrue();
        assertThat(filter.blocks("热成像能看清人吗")).isFalse();
        assertThat(filter.blocks(null)).isFalse();
        // 空的词不进词表。
        assertThat(filter.words()).containsExactly("加微信", "加vx", "刷单");
    }

    @Test
    void anEmptyListBlocksNothing() {
        assertThat(new KeywordCommentFilter(null).blocks("加微信")).isFalse();
        assertThat(new KeywordCommentFilter(List.of()).blocks("加微信")).isFalse();
    }
}
