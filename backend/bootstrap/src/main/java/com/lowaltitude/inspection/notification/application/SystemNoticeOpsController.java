package com.lowaltitude.inspection.notification.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.notification.application.SystemNotices.Action;
import com.lowaltitude.inspection.notification.application.SystemNotices.AgreementCode;
import com.lowaltitude.inspection.notification.application.SystemNotices.AudienceScope;
import com.lowaltitude.inspection.notification.application.SystemNotices.Content;
import com.lowaltitude.inspection.notification.application.SystemNotices.Draft;
import com.lowaltitude.inspection.notification.application.SystemNotices.Fact;
import com.lowaltitude.inspection.notification.application.SystemNotices.Item;
import com.lowaltitude.inspection.notification.application.SystemNotices.SystemNoticePageView;
import com.lowaltitude.inspection.notification.application.SystemNotices.SystemNoticeView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台运营发布、撤回、查看系统通知（契约 contracts/openapi-notification.yaml 1.5.0）。只给 PLATFORM_OPERATOR，
 * 没有页面（运营端 apps/admin 以后接）。发布要 Idempotency-Key：同一个键重发得到第一次那份，不再推送。
 */
@RestController
public final class SystemNoticeOpsController {

    private static final String PLATFORM_OPERATOR = "PLATFORM_OPERATOR";

    private final SystemNoticeService service;

    public SystemNoticeOpsController(SystemNoticeService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/ops/notifications/system-notices")
    public ResponseEntity<SystemNoticeView> publish(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PublishRequest request
    ) {
        UUID operator = requirePlatformOperator(authentication);
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
        requirePlatformOperator(authentication);
        return service.list(page, size);
    }

    @PostMapping("/api/v1/ops/notifications/system-notices/{noticeId}/withdraw")
    public SystemNoticeView withdraw(Authentication authentication, @PathVariable UUID noticeId) {
        requirePlatformOperator(authentication);
        return service.withdraw(noticeId);
    }

    private static UUID requirePlatformOperator(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        Set<String> roles;
        if (principal instanceof CurrentSubject current) {
            roles = current.roles();
        } else if (principal instanceof Jwt jwt) {
            List<String> claim = jwt.getClaimAsStringList("roles");
            roles = claim == null ? Set.of() : Set.copyOf(claim);
        } else {
            roles = Set.of();
        }
        if (!roles.contains(PLATFORM_OPERATOR)) {
            throw new AccessDeniedException("platform operator role is required");
        }
        return NotificationController.recipient(authentication);
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
