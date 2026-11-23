package com.lowaltitude.inspection.notification.application;

import java.util.List;

import com.lowaltitude.inspection.notification.application.NotificationRows.FakePushDeliveryRow;
import com.lowaltitude.inspection.notification.infrastructure.push.FakePushGateway;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅开发环境：看本机模拟推送（FakePushGateway）发给当前账号的最近几条，用来验收“开关关掉后不推送”。
 * 只读自己的；没有用模拟推送时一律 404。不在契约里：生产环境没有这个接口。
 */
@RestController
@Profile("dev")
public final class NotificationDevPushController {

    private static final int MAX_LIMIT = 50;

    private final PushGateway gateway;

    public NotificationDevPushController(PushGateway gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/api/v1/dev/notifications/push-deliveries")
    public DevPushDeliveries recent(Authentication authentication, @RequestParam(defaultValue = "20") int limit) {
        if (!(gateway instanceof FakePushGateway fake)) {
            throw new NotificationNotFoundException();
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit is invalid");
        }
        return new DevPushDeliveries(fake.recent(NotificationController.recipient(authentication), limit));
    }

    record DevPushDeliveries(List<FakePushDeliveryRow> items) {
    }
}
