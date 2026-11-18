package com.lowaltitude.inspection.notification.application;

import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录账号的站内信（契约 contracts/openapi-notification.yaml）。都要登录。
 * 收件人只从令牌里取，没有任何指定别人的参数；不是自己的通知一律当作不存在。
 */
@RestController
public final class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/notifications")
    public NotificationPageView list(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.list(recipient(authentication), page, size);
    }

    @GetMapping("/api/v1/notifications/unread-count")
    public UnreadCountView unreadCount(Authentication authentication) {
        return service.unreadCount(recipient(authentication));
    }

    @PostMapping("/api/v1/notifications/read-all")
    public MarkedReadView markAllRead(Authentication authentication) {
        return service.markAllRead(recipient(authentication));
    }

    @PostMapping("/api/v1/notifications/{notificationId}/read")
    public ResponseEntity<Void> markRead(
            Authentication authentication,
            @PathVariable UUID notificationId
    ) {
        if (!service.markRead(recipient(authentication), notificationId)) {
            throw new NotificationNotFoundException();
        }
        return ResponseEntity.noContent().build();
    }

    private static UUID recipient(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof CurrentSubject current) {
            return current.subjectId();
        }
        if (principal instanceof Jwt jwt) {
            try {
                return UUID.fromString(jwt.getClaimAsString("subjectId"));
            } catch (RuntimeException invalidClaim) {
                throw new AccessDeniedException("authenticated subject is invalid", invalidClaim);
            }
        }
        throw new AccessDeniedException("authenticated subject is invalid");
    }
}
