package com.lowaltitude.inspection.live.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 直播主办方和平台客服的受权接口（M20，没有页面；开发脚本 infra/scripts/dev-live-host.cjs）。
 * /api/v1/live-host/…：直播主办角色（令牌里要有机构），只碰本机构的直播间。
 * /api/v1/platform/live/…：平台客服角色，隐藏弹幕、强制结束。
 */
@RestController
public final class LiveHostController {

    private static final String HOST = "/api/v1/live-host/rooms";
    private static final String PLATFORM = "/api/v1/platform/live/rooms";

    private final LiveService service;

    public LiveHostController(LiveService service) {
        this.service = service;
    }

    // ---- 直播主办 ----

    @GetMapping(HOST)
    public LiveHostRoomPageView rooms(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return service.hostRooms(LiveSubjects.host(authentication).organizationId(), status, page, size);
    }

    /** 建直播间。第一次 201，同一个幂等键重放 200。 */
    @PostMapping(HOST)
    public ResponseEntity<LiveHostRoomView> create(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody RoomRequest request
    ) {
        LiveHostRoomCreated created = service.createRoom(LiveSubjects.host(authentication).organizationId(),
                idempotencyKey, request.draft());
        return ResponseEntity.status(created.created() ? 201 : 200).body(created.room());
    }

    @GetMapping(HOST + "/{roomId}")
    public LiveHostRoomView room(Authentication authentication, @PathVariable UUID roomId) {
        return service.hostRoom(LiveSubjects.host(authentication).organizationId(), roomId);
    }

    @PutMapping(HOST + "/{roomId}")
    public LiveHostRoomView update(Authentication authentication, @PathVariable UUID roomId,
                                   @RequestBody RoomRequest request) {
        return service.updateRoom(LiveSubjects.host(authentication).organizationId(), roomId, request.draft());
    }

    @PutMapping(HOST + "/{roomId}/replay")
    public LiveHostRoomView replay(Authentication authentication, @PathVariable UUID roomId,
                                   @RequestBody ReplayRequest request) {
        return service.setReplay(LiveSubjects.host(authentication).organizationId(), roomId, request.replayUrl(),
                request.replayPlatformName());
    }

    @PutMapping(HOST + "/{roomId}/items")
    public LiveHostRoomView items(Authentication authentication, @PathVariable UUID roomId,
                                  @RequestBody ItemsRequest request) {
        List<LiveItemDraft> items = request.items() == null ? null : request.items().stream()
                .map(item -> item == null ? null : new LiveItemDraft(item.type(), item.refId(), item.title()))
                .toList();
        return service.setItems(LiveSubjects.host(authentication).organizationId(), roomId, items,
                request.currentSeq());
    }

    @PutMapping(HOST + "/{roomId}/current-item")
    public LiveHostRoomView currentItem(Authentication authentication, @PathVariable UUID roomId,
                                        @RequestBody CurrentItemRequest request) {
        return service.setCurrentItem(LiveSubjects.host(authentication).organizationId(), roomId, request.seq());
    }

    /** 以主播身份发弹幕。第一次 201，同一个幂等键重放 200。 */
    @PostMapping(HOST + "/{roomId}/comments")
    public ResponseEntity<LiveCommentView> comment(
            Authentication authentication,
            @PathVariable UUID roomId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody LiveController.CommentRequest request
    ) {
        LiveSubjects.Subject host = LiveSubjects.host(authentication);
        LiveCommentSent sent = service.hostComment(host.organizationId(), host.subjectId(), roomId, idempotencyKey,
                request.text());
        return ResponseEntity.status(sent.created() ? 201 : 200).body(sent.comment());
    }

    @PostMapping(HOST + "/{roomId}/comments/{commentId}/hide")
    public LiveModerationView hostHide(Authentication authentication, @PathVariable UUID roomId,
                                       @PathVariable UUID commentId) {
        return service.hostHide(LiveSubjects.host(authentication).organizationId(), roomId, commentId);
    }

    // ---- 平台客服 ----

    @PostMapping(PLATFORM + "/{roomId}/comments/{commentId}/hide")
    public LiveModerationView platformHide(Authentication authentication, @PathVariable UUID roomId,
                                           @PathVariable UUID commentId) {
        LiveSubjects.platformSupport(authentication);
        return service.platformHide(roomId, commentId);
    }

    @PostMapping(PLATFORM + "/{roomId}/end")
    public LiveRoomView platformEnd(Authentication authentication, @PathVariable UUID roomId) {
        LiveSubjects.platformSupport(authentication);
        return service.platformEnd(roomId);
    }

    record RoomRequest(
            String title,
            String subtitle,
            String coverUrl,
            String cityCode,
            OffsetDateTime scheduledAt,
            String speakerName,
            String speakerTitle,
            List<String> topics
    ) {

        LiveRoomDraft draft() {
            return new LiveRoomDraft(title, subtitle, coverUrl, cityCode, scheduledAt, speakerName, speakerTitle,
                    topics);
        }
    }

    record ReplayRequest(String replayUrl, String replayPlatformName) {
    }

    record ItemRequest(String type, UUID refId, String title) {
    }

    record ItemsRequest(List<ItemRequest> items, Integer currentSeq) {
    }

    record CurrentItemRequest(Integer seq) {
    }
}
