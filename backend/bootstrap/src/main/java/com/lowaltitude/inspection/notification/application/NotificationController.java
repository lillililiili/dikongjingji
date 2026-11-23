package com.lowaltitude.inspection.notification.application;

import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.notification.application.PushDeviceService.PushDeviceView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前登录账号的站内信（契约 contracts/openapi-notification.yaml）。都要登录。
 * 收件人只从令牌里取，没有任何指定别人的参数；不是自己的通知一律当作不存在。
 */
@RestController
public final class NotificationController {

    private final NotificationService service;
    private final NotificationPreferenceService preferences;
    private final PushDeviceService devices;

    public NotificationController(NotificationService service, NotificationPreferenceService preferences,
                                  PushDeviceService devices) {
        this.service = service;
        this.preferences = preferences;
        this.devices = devices;
    }

    /**
     * systemNotices（1.5.0）：App 开通了系统通知时为 true，先把公告补记进收件箱；cityCode（可选）：App 的当前城市，
     * 发给这个城市的公告一并补记。
     */
    @GetMapping("/api/v1/notifications")
    public NotificationPageView list(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean systemNotices,
            @RequestParam(required = false) String cityCode
    ) {
        return service.list(recipient(authentication), page, size, systemNotices, cityCode);
    }

    @GetMapping("/api/v1/notifications/unread-count")
    public UnreadCountView unreadCount(Authentication authentication,
                                       @RequestParam(defaultValue = "false") boolean systemNotices,
                                       @RequestParam(required = false) String cityCode) {
        return service.unreadCount(recipient(authentication), systemNotices, cityCode);
    }

    /** 通知开关（1.5.0）：没有存过就是全开。规则见 NotificationPreferences。 */
    @GetMapping("/api/v1/notifications/preferences")
    public NotificationPreferences readPreferences(Authentication authentication) {
        return preferences.read(recipient(authentication));
    }

    /** 只改给了的开关（用 PUT 而不是 PATCH：uni.request 和小程序都不支持 PATCH）。 */
    @PutMapping("/api/v1/notifications/preferences")
    public NotificationPreferences updatePreferences(Authentication authentication,
                                                     @RequestBody PreferencesPatch patch) {
        if (patch == null) {
            throw new IllegalArgumentException("body is required");
        }
        return preferences.update(recipient(authentication), patch.orderServicePush(), patch.activityPush(),
                patch.discoverDot());
    }

    /** App 启动或登录后上报这台设备的推送标识（1.5.0）。重复上报只刷新时间和城市。 */
    @PutMapping("/api/v1/notifications/push-devices")
    public PushDeviceView registerDevice(Authentication authentication, @RequestBody PushDeviceRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("body is required");
        }
        return devices.register(recipient(authentication), request.platform(), request.pushClientId(),
                request.cityCode());
    }

    /** 退出登录前撤销这台设备：之后这个账号的推送不再发到这里。不是自己的设备也回 204。 */
    @PostMapping("/api/v1/notifications/push-devices/unregister")
    public ResponseEntity<Void> unregisterDevice(Authentication authentication,
                                                 @RequestBody PushDeviceRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("body is required");
        }
        devices.unregister(recipient(authentication), request.platform(), request.pushClientId());
        return ResponseEntity.noContent().build();
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

    record PreferencesPatch(Boolean orderServicePush, Boolean activityPush, Boolean discoverDot) {
    }

    record PushDeviceRequest(String platform, String pushClientId, String cityCode) {
    }

    static UUID recipient(Authentication authentication) {
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
