package com.lowaltitude.inspection.notification.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.notification.application.NotificationRows.MessageRow;

interface NotificationStore {

    /** 返回是否真的写入了一条；sourceKey 已存在时返回 false。 */
    boolean insert(NewNotification notification);

    long count(UUID recipientId);

    long countUnread(UUID recipientId);

    List<MessageRow> findPage(UUID recipientId, int limit, int offset);

    boolean exists(UUID recipientId, UUID id);

    int markRead(UUID recipientId, UUID id, Instant at);

    int markAllRead(UUID recipientId, Instant at);
}
