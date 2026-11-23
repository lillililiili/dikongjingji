package com.lowaltitude.inspection.notification.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.notification.application.NotificationRows.MessageRow;

interface NotificationStore {

    /** 返回是否真的写入了一条；sourceKey 已存在时返回 false。 */
    boolean insert(NewNotification notification);

    /**
     * 收件人在同一分类、同一跳转目标上没有未读的通知时才写入；返回是否写入了。
     * sourceKey 已存在时也不写。
     */
    boolean insertUnlessUnread(NewNotification notification);

    /** 把同一分类、同一跳转目标上那条未读的通知刷新成这一条的标题、正文和时间（时间只往后挪）。 */
    int refreshUnread(NewNotification notification);

    /** 把收件人在这个分类、这个跳转目标上的未读通知标为已读。 */
    int markTargetRead(UUID recipientId, NotificationCategory category, NotificationTargetType targetType,
                       UUID targetId, Instant at);

    long count(UUID recipientId);

    long countUnread(UUID recipientId);

    List<MessageRow> findPage(UUID recipientId, int limit, int offset);

    boolean exists(UUID recipientId, UUID id);

    int markRead(UUID recipientId, UUID id, Instant at);

    int markAllRead(UUID recipientId, Instant at);

    /** 把发给全体、发给 cityCode 的有效公告补记成收件人的站内信；返回新记了几条。 */
    int deliverSystemNotices(UUID recipientId, String cityCode, Instant now);
}
