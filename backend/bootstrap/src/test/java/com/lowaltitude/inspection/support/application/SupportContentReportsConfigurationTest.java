package com.lowaltitude.inspection.support.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.lowaltitude.inspection.content.application.ContentPorts;
import org.junit.jupiter.api.Test;

/** 视频评论的举报记成一条“内容有误”的客服反馈，幂等键和正文原样交给客服模块，返回反馈 ID。 */
class SupportContentReportsConfigurationTest {

    @Test
    void aReportIsRecordedAsContentFeedback() {
        SupportService support = mock(SupportService.class);
        UUID account = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        UUID feedback = UUID.randomUUID();
        UUID comment = UUID.randomUUID();
        UUID video = UUID.randomUUID();
        when(support.report(account, key, "CONTENT", "举报视频评论（其他）：……", "VIDEO_COMMENT_REPORT", comment, video))
                .thenReturn(new SupportFeedbackCreated(
                new SupportFeedbackView(feedback, SupportFeedbackType.CONTENT, "举报视频评论（其他）：……",
                        OffsetDateTime.parse("2026-11-19T10:00:00Z")), true));

        ContentPorts.Reports reports = SupportContentReportsConfiguration.reports(support);

        assertThat(reports.record(account, key, "举报视频评论（其他）：……", comment, video)).isEqualTo(feedback);
        assertThat(SupportContentReportsConfiguration.REPORT_FEEDBACK_TYPE).isEqualTo(SupportFeedbackType.CONTENT.name());
    }
}
