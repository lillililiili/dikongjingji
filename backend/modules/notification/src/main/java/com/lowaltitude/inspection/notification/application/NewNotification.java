package com.lowaltitude.inspection.notification.application;

import java.time.Instant;
import java.util.UUID;

/**
 * 一条要记下的通知。sourceKey 是产生方的幂等键：同一个 sourceKey 只会记一条。
 * targetId / targetSubId 只放稳定 ID，不放业务对象本身。
 */
public record NewNotification(
        UUID recipientId,
        NotificationCategory category,
        String eventCode,
        String title,
        String body,
        NotificationTargetType targetType,
        UUID targetId,
        UUID targetSubId,
        String sourceKey,
        Instant occurredAt
) {
}
