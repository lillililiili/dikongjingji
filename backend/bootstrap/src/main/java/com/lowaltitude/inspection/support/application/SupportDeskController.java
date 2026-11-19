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
 * 回复方的客服会话（G1，受权接口，没有页面；开发脚本 infra/scripts/dev-support-reply.cjs）。
 * /api/v1/provider/conversations…：服务商操作员（令牌里要有机构），看对方是本机构的商家、服务商会话。
 * /api/v1/platform/support/conversations…：平台客服角色，看对方是平台客服的会话。
 * 两边都只看到客户的显示名，看不到手机号和地址。
 */
@RestController
public final class SupportDeskController {

    private static final String PROVIDER = "/api/v1/provider/conversations";
    private static final String PLATFORM = "/api/v1/platform/support/conversations";

    private final SupportService service;

    public SupportDeskController(SupportService service) {
        this.service = service;
    }

    // ---- 商家、服务商 ----

    @GetMapping(PROVIDER)
    public SupportDeskConversationPageView providerConversations(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.deskConversations(organization(authentication).desk(), page, size);
    }

    @GetMapping(PROVIDER + "/{conversationId}/messages")
    public SupportDeskMessagePageView providerMessages(
            Authentication authentication,
            @PathVariable UUID conversationId,
            @RequestParam(required = false) Integer before,
            @RequestParam(required = false) Integer size
    ) {
        return service.deskMessages(organization(authentication).desk(), conversationId, before, size);
    }

    @PostMapping(PROVIDER + "/{conversationId}/messages")
    public ResponseEntity<SupportMessageView> providerReply(
            Authentication authentication,
            @PathVariable UUID conversationId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody SupportController.MessageRequest request
    ) {
        Desk desk = organization(authentication);
        return sent(service.deskSend(desk.desk(), desk.operatorId(), conversationId, idempotencyKey,
                request.draft()));
    }

    @PostMapping(PROVIDER + "/{conversationId}/read")
    public SupportDeskConversationView providerRead(Authentication authentication, @PathVariable UUID conversationId) {
        return service.deskRead(organization(authentication).desk(), conversationId);
    }

    // ---- 平台客服 ----

    @GetMapping(PLATFORM)
    public SupportDeskConversationPageView platformConversations(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.deskConversations(platform(authentication).desk(), page, size);
    }

    @GetMapping(PLATFORM + "/{conversationId}/messages")
    public SupportDeskMessagePageView platformMessages(
            Authentication authentication,
            @PathVariable UUID conversationId,
            @RequestParam(required = false) Integer before,
            @RequestParam(required = false) Integer size
    ) {
        return service.deskMessages(platform(authentication).desk(), conversationId, before, size);
    }

    @PostMapping(PLATFORM + "/{conversationId}/messages")
    public ResponseEntity<SupportMessageView> platformReply(
            Authentication authentication,
            @PathVariable UUID conversationId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody SupportController.MessageRequest request
    ) {
        Desk desk = platform(authentication);
        return sent(service.deskSend(desk.desk(), desk.operatorId(), conversationId, idempotencyKey,
                request.draft()));
    }

    @PostMapping(PLATFORM + "/{conversationId}/read")
    public SupportDeskConversationView platformRead(Authentication authentication, @PathVariable UUID conversationId) {
        return service.deskRead(platform(authentication).desk(), conversationId);
    }

    private static Desk organization(Authentication authentication) {
        SupportSubjects.Subject subject = SupportSubjects.providerOperator(authentication);
        return new Desk(SupportDesk.organization(subject.organizationId()), subject.subjectId());
    }

    private static Desk platform(Authentication authentication) {
        return new Desk(SupportDesk.platformSupport(), SupportSubjects.platformSupport(authentication).subjectId());
    }

    private static ResponseEntity<SupportMessageView> sent(SupportMessageSent sent) {
        return ResponseEntity.status(sent.created() ? 201 : 200).body(sent.message());
    }

    private record Desk(SupportDesk desk, UUID operatorId) {
    }
}
