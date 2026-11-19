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
        validate(notification);
        if (notification.category() == NotificationCategory.CUSTOMER_SERVICE) {
            // 客服消息一个会话最多一条未读，只能走 recordUnlessUnread。
            throw new IllegalArgumentException("customer service notifications use recordUnlessUnread");
        }
        return store.insert(notification);
    }

    /**
     * 记一条通知，但同一收件人在同一分类、同一跳转目标上最多一条未读（G1 客服消息：对方连着回复不刷屏）。
     * 已有未读的就把它刷新成这一条的标题、正文和时间（时间只往后挪），返回 false；新记了一条返回 true。
     */
    public boolean recordUnlessUnread(NewNotification notification) {
        validate(notification);
        if (store.insertUnlessUnread(notification)) {
            return true;
        }
        store.refreshUnread(notification);
        return false;
    }

    /** 收件人看过了这个目标（例如进了会话）：这个分类、这个目标上的未读通知标为已读。返回标了几条。 */
    public int markTargetRead(UUID recipientId, NotificationCategory category, NotificationTargetType targetType,
                              UUID targetId) {
        requireRecipient(recipientId);
        if (category == null || targetType == null || targetId == null) {
            throw new IllegalArgumentException("target is required");
        }
        return store.markTargetRead(recipientId, category, targetType, targetId, now());
    }

    private static void validate(NewNotification notification) {
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
