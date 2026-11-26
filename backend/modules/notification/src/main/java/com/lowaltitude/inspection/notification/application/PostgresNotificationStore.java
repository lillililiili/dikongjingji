package com.lowaltitude.inspection.notification.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.notification.application.NotificationRows.MessageRow;
import com.lowaltitude.inspection.notification.infrastructure.persistence.NotificationMapper;

final class PostgresNotificationStore implements NotificationStore {

    private final NotificationMapper mapper;

    PostgresNotificationStore(NotificationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean insert(NewNotification notification) {
        return mapper.insert(
                notification.recipientId(),
                notification.category().name(),
                notification.eventCode(),
                notification.title(),
                notification.body(),
                notification.targetType().name(),
                notification.targetId(),
                notification.targetSubId(),
                notification.sourceKey(),
                notification.occurredAt()) > 0;
    }

    @Override
    public boolean insertUnlessUnread(NewNotification notification) {
        return mapper.insertUnlessUnread(
                notification.recipientId(),
                notification.category().name(),
                notification.eventCode(),
                notification.title(),
                notification.body(),
                notification.targetType().name(),
                notification.targetId(),
                notification.targetSubId(),
                notification.sourceKey(),
                notification.occurredAt()) > 0;
    }

    @Override
    public int refreshUnread(NewNotification notification) {
        return mapper.refreshUnread(
                notification.recipientId(),
                notification.category().name(),
                notification.eventCode(),
                notification.title(),
                notification.body(),
                notification.targetType().name(),
                notification.targetId(),
                notification.occurredAt());
    }

    @Override
    public int markTargetRead(UUID recipientId, NotificationCategory category, NotificationTargetType targetType,
                              UUID targetId, Instant at) {
        return mapper.markTargetRead(recipientId, category.name(), targetType.name(), targetId, at);
    }

    @Override
    public long count(UUID recipientId, boolean systemNotices) {
        return mapper.count(recipientId, systemNotices);
    }

    @Override
    public long countUnread(UUID recipientId, boolean systemNotices) {
        return mapper.countUnread(recipientId, systemNotices);
    }

    @Override
    public List<MessageRow> findPage(UUID recipientId, int limit, int offset, boolean systemNotices) {
        return mapper.findPage(recipientId, limit, offset, systemNotices);
    }

    @Override
    public boolean exists(UUID recipientId, UUID id) {
        return mapper.countOwned(recipientId, id) > 0;
    }

    @Override
    public int markRead(UUID recipientId, UUID id, Instant at) {
        return mapper.markRead(recipientId, id, at);
    }

    @Override
    public int markAllRead(UUID recipientId, Instant at, boolean systemNotices) {
        return mapper.markAllRead(recipientId, at, systemNotices);
    }

    @Override
    public int deliverSystemNotices(UUID recipientId, String cityCode, Instant now) {
        return mapper.deliverSystemNotices(recipientId, cityCode, now);
    }
}
