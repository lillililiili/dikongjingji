package com.lowaltitude.inspection.support.application;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户的客服会话和意见反馈（G1，契约 contracts/openapi-support.yaml 1.0.0）。都要登录和客户角色；账号只从令牌里取，
 * 不是自己的会话一律当作不存在。
 */
@RestController
public final class SupportController {

    private final SupportService service;

    public SupportController(SupportService service) {
        this.service = service;
    }

    /** 我的会话（只列发过消息的），按最后一条消息排；unreadCount 是所有会话的未读合计。 */
    @GetMapping("/api/v1/conversations")
    public SupportConversationPageView conversations(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.conversations(SupportSubjects.customer(authentication).subjectId(), page, size);
    }

    /** 打开和这一方的会话：新开 201，已有就返回原来的 200。平台客服不带机构 ID。 */
    @PostMapping("/api/v1/conversations")
    public ResponseEntity<SupportConversationView> open(Authentication authentication,
                                                        @RequestBody OpenRequest request) {
        SupportConversationOpened opened = service.open(SupportSubjects.customer(authentication).subjectId(),
                request.counterpartKind(), request.organizationId());
        return ResponseEntity.status(opened.created() ? 201 : 200).body(opened.conversation());
    }

    /** 一页消息，按时间先后；更早的用 before（上一页的 nextBefore）再取。 */
    @GetMapping("/api/v1/conversations/{conversationId}/messages")
    public SupportMessagePageView messages(
            Authentication authentication,
            @PathVariable UUID conversationId,
            @RequestParam(required = false) Integer before,
            @RequestParam(required = false) Integer size
    ) {
        return service.messages(SupportSubjects.customer(authentication).subjectId(), conversationId, before, size);
    }

    /** 发一条（文字或卡片）。第一次 201，同一个幂等键重放 200。 */
    @PostMapping("/api/v1/conversations/{conversationId}/messages")
    public ResponseEntity<SupportMessageView> send(
            Authentication authentication,
            @PathVariable UUID conversationId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody MessageRequest request
    ) {
        SupportMessageSent sent = service.send(SupportSubjects.customer(authentication).subjectId(), conversationId,
                idempotencyKey, request.draft());
        return ResponseEntity.status(sent.created() ? 201 : 200).body(sent.message());
    }

    /** 进了会话：未读清零，这个会话的“客服消息”站内信标为已读。 */
    @PostMapping("/api/v1/conversations/{conversationId}/read")
    public SupportConversationView read(Authentication authentication, @PathVariable UUID conversationId) {
        return service.read(SupportSubjects.customer(authentication).subjectId(), conversationId);
    }

    /** 提交反馈。第一次 201，同一个幂等键重放 200。 */
    @PostMapping("/api/v1/feedback")
    public ResponseEntity<SupportFeedbackView> feedback(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody FeedbackRequest request
    ) {
        SupportFeedbackCreated created = service.feedback(SupportSubjects.customer(authentication).subjectId(),
                idempotencyKey, request.type(), request.text());
        return ResponseEntity.status(created.created() ? 201 : 200).body(created.feedback());
    }

    /**
     * @param counterpartKind PLATFORM / MERCHANT / PROVIDER
     * @param organizationId  商家、服务商的机构 ID；平台客服不带
     */
    record OpenRequest(String counterpartKind, UUID organizationId) {
    }

    /** text 和 card 只给一个。 */
    record MessageRequest(String text, CardRequest card) {

        SupportMessageDraft draft() {
            return new SupportMessageDraft(text, card == null ? null
                    : new SupportCardDraft(card.type(), card.refId(), card.title(), card.subtitle()));
        }
    }

    record CardRequest(String type, UUID refId, String title, String subtitle) {
    }

    record FeedbackRequest(String type, String text) {
    }
}
