package com.lowaltitude.inspection.notification.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.notification.application.NotificationRows.SystemNoticeRow;

interface SystemNoticeStore {

    /** 返回是否真的登记了；幂等键已存在时返回 false。 */
    boolean insert(UUID id, String title, String summary, String content, String aboutAgreement, String audienceScope,
                   String cityCode, UUID publishedBy, Instant publishedAt, Instant expiresAt, String publishKey);

    Optional<SystemNoticeRow> findByPublishKey(String publishKey);

    Optional<SystemNoticeRow> find(UUID id);

    Optional<SystemNoticeRow> latestAbout(String agreement, Instant now);

    List<SystemNoticeRow> page(int limit, int offset);

    long count();

    int withdraw(UUID id, Instant at);

    int hideDelivered(UUID id, Instant at);
}
