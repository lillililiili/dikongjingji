package com.lowaltitude.inspection.notification.application;

import java.time.Instant;
import java.util.UUID;

public final class NotificationRows {

    private NotificationRows() {
    }

    public record MessageRow(
            UUID id,
            String category,
            String eventCode,
            String title,
            String body,
            String targetType,
            UUID targetId,
            UUID targetSubId,
            Instant occurredAt,
            Instant readAt
    ) {
    }
}
