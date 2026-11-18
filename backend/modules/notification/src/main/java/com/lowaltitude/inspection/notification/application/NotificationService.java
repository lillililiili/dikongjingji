package com.lowaltitude.inspection.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 站内信：记录一条通知，和“收件人读自己的通知”（契约 contracts/openapi-notification.yaml）。
 * 所有读写都以收件人为条件；别人的通知读不到、也标不了已读。
 */
public class NotificationService {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_TITLE_LENGTH = 80;
    private static final int MAX_BODY_LENGTH = 300;
    private static final int MAX_EVENT_CODE_LENGTH = 80;
    private static final int MAX_SOURCE_KEY_LENGTH = 120;

    private final NotificationStore store;
    private final Clock clock;

    NotificationService(NotificationStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * 记一条通知。同一个 sourceKey 重复调用只记一次，返回是否新记了一条。
     * 调用方的事务里调用它，通知和业务状态一起提交或一起回滚。
     */
    public boolean record(NewNotification notification) {
        if (notification == null
                || notification.recipientId() == null
                || notification.category() == null
                || notification.targetType() == null
                || notification.targetId() == null
                || notification.occurredAt() == null
                || invalid(notification.eventCode(), MAX_EVENT_CODE_LENGTH)
                || invalid(notification.title(), MAX_TITLE_LENGTH)
                || invalid(notification.body(), MAX_BODY_LENGTH)
                || invalid(notification.sourceKey(), MAX_SOURCE_KEY_LENGTH)) {
            throw new IllegalArgumentException("notification is invalid");
        }
        return store.insert(notification);
    }

    public NotificationPageView list(UUID recipientId, int page, int size) {
        requireRecipient(recipientId);
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        long total = store.count(recipientId);
        List<NotificationView> items = store
                .findPage(recipientId, size, Math.multiplyExact(page - 1, size))
                .stream()
                .map(row -> new NotificationView(
                        row.id(),
                        NotificationCategory.valueOf(row.category()),
                        row.eventCode(),
                        row.title(),
                        row.body(),
                        new NotificationTargetView(
                                NotificationTargetType.valueOf(row.targetType()),
                                row.targetId(),
                                row.targetSubId()),
                        row.occurredAt(),
                        row.readAt() != null))
                .toList();
        return new NotificationPageView(
                page, size, total, (long) page * size < total, store.countUnread(recipientId), items);
    }

    public UnreadCountView unreadCount(UUID recipientId) {
        requireRecipient(recipientId);
        return new UnreadCountView(store.countUnread(recipientId));
    }

    /** 标一条为已读；已经读过的再标一次没有变化。不是这个收件人的通知返回 false。 */
    public boolean markRead(UUID recipientId, UUID notificationId) {
        requireRecipient(recipientId);
        if (notificationId == null) {
            throw new IllegalArgumentException("notification id is required");
        }
        if (!store.exists(recipientId, notificationId)) {
            return false;
        }
        store.markRead(recipientId, notificationId, now());
        return true;
    }

    public MarkedReadView markAllRead(UUID recipientId) {
        requireRecipient(recipientId);
        return new MarkedReadView(store.markAllRead(recipientId, now()));
    }

    private Instant now() {
        return clock.instant();
    }

    private static void requireRecipient(UUID recipientId) {
        if (recipientId == null) {
            throw new IllegalArgumentException("recipient is required");
        }
    }

    private static boolean invalid(String value, int maxLength) {
        return value == null || value.isBlank() || value.length() > maxLength;
    }
}

record NotificationTargetView(NotificationTargetType type, UUID id, UUID subId) {
}

record NotificationView(
        UUID id,
        NotificationCategory category,
        String eventCode,
        String title,
        String body,
        NotificationTargetView target,
        Instant occurredAt,
        boolean read
) {
}

record NotificationPageView(
        int page,
        int size,
        long total,
        boolean hasMore,
        long unreadCount,
        List<NotificationView> items
) {
}

record UnreadCountView(long count) {
}

record MarkedReadView(int updated) {
}
