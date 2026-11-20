package com.lowaltitude.inspection.content.application;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发现页视频（契约 contracts/openapi-content.yaml 1.2.0）。视频详情、视频流和评论列表不登录也能读（登录了带上自己的
 * 点赞、关注、“我发的”）；点赞、关注、发评论、举报要登录；隐藏评论是受权接口（平台客服或发布机构的操作员）。
 * 必填的 cityCode 声明为 required = false，缺失时由服务按参数无效处理并返回 400。
 */
@RestController
public final class ContentVideoController {

    private final ContentVideoService service;

    public ContentVideoController(ContentVideoService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/content/videos")
    public ContentVideoPageView stream(
            Authentication authentication,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String feed,
            @RequestParam(required = false) UUID startVideoId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size
    ) {
        return service.stream(cityCode, feed, startVideoId, cursor, size, ContentSubjects.viewer(authentication));
    }

    @GetMapping("/api/v1/content/videos/{videoId}")
    public ContentVideoView video(
            Authentication authentication,
            @PathVariable UUID videoId,
            @RequestParam(required = false) String cityCode
    ) {
        return service.video(videoId, cityCode, ContentSubjects.viewer(authentication));
    }

    @PutMapping("/api/v1/content/videos/{videoId}/like")
    public ContentVideoLikeView like(Authentication authentication, @PathVariable UUID videoId) {
        return service.like(videoId, ContentSubjects.actor(authentication));
    }

    @DeleteMapping("/api/v1/content/videos/{videoId}/like")
    public ContentVideoLikeView unlike(Authentication authentication, @PathVariable UUID videoId) {
        return service.unlike(videoId, ContentSubjects.actor(authentication));
    }

    @GetMapping("/api/v1/content/videos/{videoId}/comments")
    public ContentCommentPageView comments(
            Authentication authentication,
            @PathVariable UUID videoId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.comments(videoId, page, size, ContentSubjects.viewer(authentication));
    }

    /** 发评论：第一次 201，同一个幂等键重放 200。 */
    @PostMapping("/api/v1/content/videos/{videoId}/comments")
    public ResponseEntity<ContentCommentView> comment(
            Authentication authentication,
            @PathVariable UUID videoId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody CommentRequest request
    ) {
        ContentCommentPosted posted = service.comment(videoId, ContentSubjects.actor(authentication), idempotencyKey,
                request.text());
        return ResponseEntity.status(posted.created() ? 201 : 200).body(posted.comment());
    }

    /** 举报：第一次 201，这个账号举报过这一条了 200。 */
    @PostMapping("/api/v1/content/comments/{commentId}/reports")
    public ResponseEntity<ContentCommentReportView> report(
            Authentication authentication,
            @PathVariable UUID commentId,
            @RequestBody ReportRequest request
    ) {
        ContentCommentReported reported = service.report(commentId, ContentSubjects.actor(authentication),
                request.reason());
        return ResponseEntity.status(reported.created() ? 201 : 200).body(reported.report());
    }

    /** 受权接口（没有页面）：平台客服，或发布这条视频的机构的操作员。 */
    @PostMapping("/api/v1/content/comments/{commentId}/hide")
    public ContentCommentHiddenView hide(Authentication authentication, @PathVariable UUID commentId) {
        return service.hide(commentId, ContentSubjects.moderator(authentication));
    }

    @PutMapping("/api/v1/content/follows/{organizationId}")
    public ContentFollowView follow(Authentication authentication, @PathVariable UUID organizationId) {
        return service.follow(organizationId, ContentSubjects.actor(authentication));
    }

    @DeleteMapping("/api/v1/content/follows/{organizationId}")
    public ContentFollowView unfollow(Authentication authentication, @PathVariable UUID organizationId) {
        return service.unfollow(organizationId, ContentSubjects.actor(authentication));
    }

    record CommentRequest(String text) {
    }

    /** @param reason AD_SPAM / ABUSE / ILLEGAL / OTHER */
    record ReportRequest(String reason) {
    }
}
