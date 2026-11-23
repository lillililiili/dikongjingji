package com.lowaltitude.inspection.notification.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.notification.application.SystemNotices.Action;
import com.lowaltitude.inspection.notification.application.SystemNotices.AgreementCode;
import com.lowaltitude.inspection.notification.application.SystemNotices.AudienceScope;
import com.lowaltitude.inspection.notification.application.SystemNotices.Content;
import com.lowaltitude.inspection.notification.application.SystemNotices.Draft;
import com.lowaltitude.inspection.notification.application.SystemNotices.Fact;
import com.lowaltitude.inspection.notification.application.SystemNotices.Item;
import com.lowaltitude.inspection.notification.application.SystemNotices.SystemNoticePageView;
import com.lowaltitude.inspection.notification.application.SystemNotices.SystemNoticeView;
import com.lowaltitude.inspection.ops.OpsActors;
import org.springframework.http.HttpStatus;
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
 * 平台运营发布、撤回、查看系统通知（契约 contracts/openapi-notification.yaml 1.5.0）。只给 PLATFORM_OPERATOR，
 * 页面在运营端 apps/admin 的“系统通知”。操作人取自共享的 OpsActors。发布要 Idempotency-Key：同一个键重发得到第一次那份，
 * 不再推送。
 */
@RestController
public final class SystemNoticeOpsController {

    private final SystemNoticeService service;
    private final OpsActors actors;

    public SystemNoticeOpsController(SystemNoticeService service, OpsActors actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping("/api/v1/ops/notifications/system-notices")
    public ResponseEntity<SystemNoticeView> publish(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PublishRequest request
    ) {
        UUID operator = actors.operator(authentication).accountId();
        if (request == null) {
            throw new IllegalArgumentException("body is required");
        }
        Draft draft = new Draft(
                request.title(),
                request.summary(),
                new Content(request.paragraphs(), request.facts(), request.items(), request.footnote(),
                        request.action()),
                request.aboutAgreement(),
                request.audience() == null ? null : request.audience().scope(),
                request.audience() == null ? null : request.audience().cityCode(),
                request.expiresAt());
        return ResponseEntity.status(HttpStatus.CREATED).body(service.publish(operator, idempotencyKey, draft));
    }

    @GetMapping("/api/v1/ops/notifications/system-notices")
    public SystemNoticePageView list(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        actors.operator(authentication);
        return service.list(page, size);
    }

    @PostMapping("/api/v1/ops/notifications/system-notices/{noticeId}/withdraw")
    public SystemNoticeView withdraw(Authentication authentication, @PathVariable UUID noticeId) {
        actors.operator(authentication);
        return service.withdraw(noticeId);
    }

    record AudienceRequest(AudienceScope scope, String cityCode) {
    }

    record PublishRequest(
            String title,
            String summary,
            List<String> paragraphs,
            List<Fact> facts,
            List<Item> items,
            String footnote,
            Action action,
            AgreementCode aboutAgreement,
            AudienceRequest audience,
            Instant expiresAt
    ) {
    }
}
