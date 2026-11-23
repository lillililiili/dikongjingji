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

    public record PushDeviceRow(UUID id, UUID accountId, String platform, String pushClientId) {
    }

    public record PreferenceRow(boolean orderServicePush, boolean activityPush, boolean discoverDot) {
    }

    /** content 是 jsonb 的文本：{paragraphs, facts, items, footnote, action}。 */
    public record SystemNoticeRow(
            UUID id,
            String title,
            String summary,
            String content,
            String aboutAgreement,
            String audienceScope,
            String cityCode,
            Instant publishedAt,
            Instant expiresAt,
            String status,
            Instant withdrawnAt
    ) {
    }

    public record FakePushDeliveryRow(
            UUID id,
            UUID deviceId,
            String platform,
            String category,
            String title,
            String body,
            String targetType,
            UUID targetId,
            Instant createdAt
    ) {
    }
}
