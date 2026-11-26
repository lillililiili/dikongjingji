package com.lowaltitude.inspection.support.application;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.ops.OpsTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 运营端“举报与反馈”的控制器（S3-1）：只给平台客服，处理人取自令牌和身份资料。 */
class OpsFeedbackControllerTest {

    private static final UUID FEEDBACK = UUID.fromString("e7000000-0000-4000-8000-000000000001");
    private static final UUID COMMENT = UUID.fromString("f4100000-0000-4000-8000-000000000001");
    private static final UUID ROOM = UUID.fromString("f4000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-22T02:00:00Z");

    private final SupportService service = mock(SupportService.class);
    private final MockMvc mockMvc = OpsTestSupport.mockMvc(new OpsFeedbackController(service, OpsTestSupport.actors()));

    private static OpsFeedbackView view(SupportFeedbackHandleStatus status) {
        return new OpsFeedbackView(FEEDBACK, SupportFeedbackType.CONTENT, SupportFeedbackSource.LIVE_COMMENT_REPORT,
                "举报直播弹幕：第 3 条", new SupportReportTargetView(COMMENT, ROOM, "HIDDEN", "测试弹幕"),
                new SupportFeedbackReporterView("王女士（示例）"), status,
                status == SupportFeedbackHandleStatus.HANDLED
                        ? new SupportFeedbackHandlingView("已隐藏该弹幕", OpsTestSupport.SUPPORT_NAME, AT) : null,
                status == SupportFeedbackHandleStatus.HANDLED ? 1 : 0, AT);
    }

    @Test
    void supportListsAndHandlesWithItsOwnName() throws Exception {
        when(service.opsFeedback("PENDING", "LIVE_COMMENT_REPORT", 1, 20)).thenReturn(new OpsFeedbackPageView(1, 20, 1,
                false, 3, List.of(view(SupportFeedbackHandleStatus.PENDING))));
        when(service.handleFeedback(FEEDBACK, "已隐藏该弹幕", 0, OpsTestSupport.SUPPORT_ID, OpsTestSupport.SUPPORT_NAME))
                .thenReturn(view(SupportFeedbackHandleStatus.HANDLED));

        mockMvc.perform(get("/api/v1/ops/feedback?handleStatus=PENDING&source=LIVE_COMMENT_REPORT")
                        .principal(OpsTestSupport.support()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingCount").value(3))
                .andExpect(jsonPath("$.items[0].target.parentId").value(ROOM.toString()))
                .andExpect(jsonPath("$.items[0].target.visibility").value("HIDDEN"))
                .andExpect(jsonPath("$.items[0].target.text").value("测试弹幕"))
                .andExpect(jsonPath("$.items[0].handling").isEmpty());
        mockMvc.perform(post("/api/v1/ops/feedback/" + FEEDBACK + "/handle").principal(OpsTestSupport.support())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"已隐藏该弹幕\",\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.handling.handlerName").value(OpsTestSupport.SUPPORT_NAME));
    }

    @Test
    void handledTwiceOrStaleIsAConflict() throws Exception {
        when(service.handleFeedback(FEEDBACK, "再处理", 1, OpsTestSupport.SUPPORT_ID, OpsTestSupport.SUPPORT_NAME))
                .thenThrow(new SupportException(SupportException.Error.ALREADY_HANDLED));
        when(service.handleFeedback(FEEDBACK, "旧版本", 0, OpsTestSupport.SUPPORT_ID, OpsTestSupport.SUPPORT_NAME))
                .thenThrow(new SupportException(SupportException.Error.VERSION_CONFLICT));

        mockMvc.perform(post("/api/v1/ops/feedback/" + FEEDBACK + "/handle").principal(OpsTestSupport.support())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"再处理\",\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPS_FEEDBACK_ALREADY_HANDLED"));
        mockMvc.perform(post("/api/v1/ops/feedback/" + FEEDBACK + "/handle").principal(OpsTestSupport.support())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"旧版本\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPS_VERSION_CONFLICT"));
    }

    @Test
    void platformOperatorsAndOthersAreForbidden() throws Exception {
        for (var who : List.of(OpsTestSupport.operator(),
                OpsTestSupport.signedIn(OpsTestSupport.SUPPORT_ID, "CUSTOMER"),
                OpsTestSupport.signedIn(UUID.randomUUID(), "PLATFORM_SUPPORT"))) {
            mockMvc.perform(get("/api/v1/ops/feedback").principal(who)).andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/ops/feedback/" + FEEDBACK + "/handle").principal(who)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\",\"version\":0}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }
}
