package com.lowaltitude.inspection.support.application;

import java.util.UUID;

import com.lowaltitude.inspection.ops.OpsActor;
import com.lowaltitude.inspection.ops.OpsActors;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端“举报与反馈”（S3-1，契约 contracts/openapi-ops-feedback.yaml 1.0.0）。只给平台客服（PLATFORM_SUPPORT）。
 * 隐藏被举报的评论、弹幕不在这里：页面直接调内容模块（POST /api/v1/content/comments/{commentId}/hide）和直播模块
 * （POST /api/v1/platform/live/rooms/{roomId}/comments/{commentId}/hide）已有的受权接口，平台客服本来就有权调。
 */
@RestController
public final class OpsFeedbackController {

    private static final String FEEDBACK = "/api/v1/ops/feedback";

    private final SupportService service;
    private final OpsActors actors;

    public OpsFeedbackController(SupportService service, OpsActors actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping(FEEDBACK)
    public OpsFeedbackPageView feedback(
            Authentication authentication,
            @RequestParam(required = false) String handleStatus,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        actors.support(authentication);
        return service.opsFeedback(handleStatus, source, page, size);
    }

    @PostMapping(FEEDBACK + "/{feedbackId}/handle")
    public OpsFeedbackView handle(
            Authentication authentication,
            @PathVariable UUID feedbackId,
            @RequestBody HandleRequest request
    ) {
        OpsActor actor = actors.support(authentication);
        return service.handleFeedback(feedbackId, request.note(), request.version(), actor.accountId(),
                actor.displayName());
    }

    /**
     * @param note    处理说明（1–200 字）
     * @param version 页面读到的版本号
     */
    record HandleRequest(String note, Integer version) {
    }
}
