package com.lowaltitude.inspection.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

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

    private static final String CITY_CODE_PATTERN = "^[A-Z0-9]{4,16}$";

    private final NotificationStore store;
    private final Clock clock;
    // 新记了一条之后（S5-9 推送）；由配置接到 PushDispatcher，在事务提交之后按通知开关推送。
    private final Consumer<NewNotification> onRecorded;

    NotificationService(NotificationStore store, Clock clock) {
        this(store, clock, notification -> { });
    }

    NotificationService(NotificationStore store, Clock clock, Consumer<NewNotification> onRecorded) {
        this.store = store;
        this.clock = clock;
        this.onRecorded = onRecorded;
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
        if (notification.category() == NotificationCategory.SYSTEM_NOTICE) {
            // 系统通知由公告补记（deliverSystemNotices），不由业务直接写。
            throw new IllegalArgumentException("system notices are delivered from published notices");
        }
        boolean recorded = store.insert(notification);
        if (recorded) {
            onRecorded.accept(notification);
        }
        return recorded;
    }

    /**
     * 记一条通知，但同一收件人在同一分类、同一跳转目标上最多一条未读（G1 客服消息：对方连着回复不刷屏）。
     * 已有未读的就把它刷新成这一条的标题、正文和时间（时间只往后挪），返回 false；新记了一条返回 true。
     */
    public boolean recordUnlessUnread(NewNotification notification) {
        validate(notification);
        if (store.insertUnlessUnread(notification)) {
            // 只有新记一条时推送；对方连着回复只刷新那条未读，不再推。
            onRecorded.accept(notification);
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
        return list(recipientId, page, size, false, null);
    }

    /**
     * 一页站内信。systemNotices 为 true 时（App 开通了系统通知的构建），先把发给全体、发给 cityCode（App 的当前城市，
     * 可以不给）的系统通知补记成这个账号的（每份只记一次），再读。没开通的构建从查询中过滤已投递的系统通知。
     */
    public NotificationPageView list(UUID recipientId, int page, int size, boolean systemNotices, String cityCode) {
        requireRecipient(recipientId);
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        deliverSystemNotices(recipientId, systemNotices, cityCode);
        long total = store.count(recipientId, systemNotices);
        List<NotificationView> items = store
                .findPage(recipientId, size, Math.multiplyExact(page - 1, size), systemNotices)
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
                page, size, total, (long) page * size < total, store.countUnread(recipientId, systemNotices), items);
    }

    public UnreadCountView unreadCount(UUID recipientId) {
        return unreadCount(recipientId, false, null);
    }

    /** 未读数；和列表一样，systemNotices 为 true 时先补记系统通知，底栏角标才数得到。 */
    public UnreadCountView unreadCount(UUID recipientId, boolean systemNotices, String cityCode) {
        requireRecipient(recipientId);
        deliverSystemNotices(recipientId, systemNotices, cityCode);
        return new UnreadCountView(store.countUnread(recipientId, systemNotices));
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
        return markAllRead(recipientId, true);
    }

    public MarkedReadView markAllRead(UUID recipientId, boolean systemNotices) {
        requireRecipient(recipientId);
        return new MarkedReadView(store.markAllRead(recipientId, now(), systemNotices));
    }

    private void deliverSystemNotices(UUID recipientId, boolean systemNotices, String cityCode) {
        if (cityCode != null && !cityCode.matches(CITY_CODE_PATTERN)) {
            throw new IllegalArgumentException("city code is invalid");
        }
        if (systemNotices) {
            store.deliverSystemNotices(recipientId, cityCode, now());
        }
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
