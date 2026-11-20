package com.lowaltitude.inspection.live.application;

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
 * 直播的观看端（M20，契约 contracts/openapi-live.yaml 1.0.0）。列表、详情、弹幕增量和记一次观看公开；
 * 其余要登录和客户角色，账号只从令牌里取。
 */
@RestController
public final class LiveController {

    private final LiveService service;

    public LiveController(LiveService service) {
        this.service = service;
    }

    // ---- 公开 ----

    @GetMapping("/api/v1/live/rooms")
    public LiveRoomPageView rooms(
            @RequestParam String cityCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return service.rooms(cityCode, status, page, size);
    }

    @GetMapping("/api/v1/live/rooms/{roomId}")
    public LiveRoomView room(@PathVariable UUID roomId, @RequestParam String cityCode) {
        return service.room(roomId, cityCode);
    }

    @GetMapping("/api/v1/live/rooms/{roomId}/comments")
    public LiveCommentPageView comments(
            @PathVariable UUID roomId,
            @RequestParam(required = false) Long since,
            @RequestParam(required = false) Integer size
    ) {
        return service.comments(roomId, since, size);
    }

    @PostMapping("/api/v1/live/rooms/{roomId}/views")
    public LiveViewCountView view(@PathVariable UUID roomId, @RequestBody ViewRequest request) {
        return service.recordView(roomId, request.deviceId());
    }

    // ---- 观众 ----

    @GetMapping("/api/v1/live/rooms/{roomId}/me")
    public LiveViewerStateView me(Authentication authentication, @PathVariable UUID roomId) {
        return service.viewerState(LiveSubjects.customer(authentication).subjectId(), roomId);
    }

    @PutMapping("/api/v1/live/rooms/{roomId}/reminder")
    public LiveViewerStateView remind(Authentication authentication, @PathVariable UUID roomId) {
        return service.remind(LiveSubjects.customer(authentication).subjectId(), roomId);
    }

    @DeleteMapping("/api/v1/live/rooms/{roomId}/reminder")
    public LiveViewerStateView cancelReminder(Authentication authentication, @PathVariable UUID roomId) {
        return service.cancelReminder(LiveSubjects.customer(authentication).subjectId(), roomId);
    }

    /** 发弹幕。第一次 201，同一个幂等键重放 200。 */
    @PostMapping("/api/v1/live/rooms/{roomId}/comments")
    public ResponseEntity<LiveCommentView> comment(
            Authentication authentication,
            @PathVariable UUID roomId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody CommentRequest request
    ) {
        LiveCommentSent sent = service.comment(LiveSubjects.customer(authentication).subjectId(), roomId,
                idempotencyKey, request.text());
        return ResponseEntity.status(sent.created() ? 201 : 200).body(sent.comment());
    }

    @PostMapping("/api/v1/live/rooms/{roomId}/likes")
    public LiveLikeView like(Authentication authentication, @PathVariable UUID roomId,
                             @RequestBody LikeRequest request) {
        return service.like(LiveSubjects.customer(authentication).subjectId(), roomId,
                request.count() == null ? 0 : request.count());
    }

    @PostMapping("/api/v1/live/rooms/{roomId}/comments/{commentId}/report")
    public LiveReportView report(Authentication authentication, @PathVariable UUID roomId,
                                 @PathVariable UUID commentId) {
        return service.report(LiveSubjects.customer(authentication).subjectId(), roomId, commentId);
    }

    @PutMapping("/api/v1/live/hosts/{organizationId}/follow")
    public LiveFollowView follow(Authentication authentication, @PathVariable UUID organizationId) {
        return service.follow(LiveSubjects.customer(authentication).subjectId(), organizationId);
    }

    @DeleteMapping("/api/v1/live/hosts/{organizationId}/follow")
    public LiveFollowView unfollow(Authentication authentication, @PathVariable UUID organizationId) {
        return service.unfollow(LiveSubjects.customer(authentication).subjectId(), organizationId);
    }

    /** @param deviceId App 在本机生成并保存的随机标识 */
    record ViewRequest(UUID deviceId) {
    }

    record CommentRequest(String text) {
    }

    record LikeRequest(Integer count) {
    }
}
