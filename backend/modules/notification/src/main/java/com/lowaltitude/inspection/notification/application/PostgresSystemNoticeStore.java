package com.lowaltitude.inspection.notification.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.notification.application.NotificationRows.SystemNoticeRow;
import com.lowaltitude.inspection.notification.infrastructure.persistence.SystemNoticeMapper;

final class PostgresSystemNoticeStore implements SystemNoticeStore {

    private final SystemNoticeMapper mapper;

    PostgresSystemNoticeStore(SystemNoticeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean insert(UUID id, String title, String summary, String content, String aboutAgreement,
                          String audienceScope, String cityCode, UUID publishedBy, Instant publishedAt,
                          Instant expiresAt, String publishKey) {
        return mapper.insert(id, title, summary, content, aboutAgreement, audienceScope, cityCode, publishedBy,
                publishedAt, expiresAt, publishKey) > 0;
    }

    @Override
    public Optional<SystemNoticeRow> findByPublishKey(String publishKey) {
        return Optional.ofNullable(mapper.findByPublishKey(publishKey));
    }

    @Override
    public Optional<SystemNoticeRow> find(UUID id) {
        return Optional.ofNullable(mapper.find(id));
    }

    @Override
    public Optional<SystemNoticeRow> latestAbout(String agreement, Instant now) {
        return Optional.ofNullable(mapper.latestAbout(agreement, now));
    }

    @Override
    public List<SystemNoticeRow> page(int limit, int offset) {
        return mapper.page(limit, offset);
    }

    @Override
    public long count() {
        return mapper.count();
    }

    @Override
    public int withdraw(UUID id, Instant at) {
        return mapper.withdraw(id, at);
    }

    @Override
    public int hideDelivered(UUID id, Instant at) {
        return mapper.hideDelivered(id, at);
    }
}
