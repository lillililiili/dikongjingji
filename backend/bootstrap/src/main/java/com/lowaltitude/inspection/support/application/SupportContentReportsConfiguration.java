package com.lowaltitude.inspection.support.application;

import com.lowaltitude.inspection.content.application.ContentPorts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 发现页视频（1.2.0）的评论举报记进客服反馈：类型“内容有误”（CONTENT），正文是内容模块写好的说明，
 * 幂等键由内容模块按评论算出，同一账号再举报同一条拿到的是第一次那条反馈。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class SupportContentReportsConfiguration {

    static final String REPORT_FEEDBACK_TYPE = "CONTENT";

    @Bean
    ContentPorts.Reports contentReports(SupportService support) {
        return reports(support);
    }

    static ContentPorts.Reports reports(SupportService support) {
        return (accountId, idempotencyKey, text, commentId, videoId) -> support.report(accountId, idempotencyKey,
                REPORT_FEEDBACK_TYPE, text, SupportFeedbackSource.VIDEO_COMMENT_REPORT.name(), commentId, videoId)
                .feedback().feedbackId();
    }
}
