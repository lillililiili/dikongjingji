package com.lowaltitude.inspection.live.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.support.application.SupportService;
import org.junit.jupiter.api.Test;

/** 直播的端口接到客服反馈（举报弹幕）；启动配置里的默认词表拦得住私下交易，不误拦正常提问。 */
class LivePortsConfigurationTest {

    @Test
    void aReportIsRecordedAsContentFeedbackWithItsKey() {
        SupportService support = mock(SupportService.class);
        UUID account = UUID.randomUUID();
        UUID key = UUID.randomUUID();

        UUID room = UUID.randomUUID();
        UUID comment = UUID.randomUUID();
        LivePortsConfiguration.reports(support).report(account, key, "举报直播弹幕：《机器狗园区夜巡演示》第 3 条「加微信」",
                room, comment);

        // S3-1：举报带上弹幕和它所在的直播间，运营端据此隐藏弹幕。
        verify(support).report(account, key, "CONTENT", "举报直播弹幕：《机器狗园区夜巡演示》第 3 条「加微信」",
                "LIVE_COMMENT_REPORT", comment, room);
    }

    @Test
    void theDefaultWordListBlocksPrivateDealsOnly() throws Exception {
        // 测试资源里也有一份 application.yml，类路径上会挡住正式的那份，所以按文件读。
        String yaml = java.nio.file.Files.readString(java.nio.file.Path.of("src", "main", "resources",
                "application.yml"), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("blocked-words: \\$\\{LIVE_COMMENT_BLOCKED_WORDS:([^}]*)}").matcher(yaml);
        assertThat(matcher.find()).isTrue();
        KeywordCommentFilter filter = new KeywordCommentFilter(Arrays.asList(matcher.group(1).split(",")));

        assertThat(filter.blocks("可以私下交易便宜点")).isTrue();
        assertThat(filter.blocks("加 V 信详聊")).isTrue();
        assertThat(filter.blocks("能开发票吗")).isFalse();
        assertThat(filter.blocks("热成像能看清人吗")).isFalse();
        assertThat(yaml).contains("provider: ${LIVE_STREAM_PROVIDER:none}");
    }
}
