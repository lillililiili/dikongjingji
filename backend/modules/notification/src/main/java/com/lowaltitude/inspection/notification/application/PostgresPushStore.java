package com.lowaltitude.inspection.notification.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.notification.application.NotificationRows.PushDeviceRow;
import com.lowaltitude.inspection.notification.infrastructure.persistence.PushDeviceMapper;

final class PostgresPushStore implements PushStore {

    private final PushDeviceMapper mapper;

    PostgresPushStore(PushDeviceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<NotificationPreferences> findPreferences(UUID accountId) {
        return Optional.ofNullable(mapper.findPreference(accountId))
                .map(row -> new NotificationPreferences(row.orderServicePush(), row.activityPush(), row.discoverDot()));
    }

    @Override
    public void savePreferences(UUID accountId, NotificationPreferences preferences, Instant at) {
        mapper.savePreference(accountId, preferences.orderServicePush(), preferences.activityPush(),
                preferences.discoverDot(), at);
    }

    @Override
    public UUID registerDevice(UUID accountId, String platform, String pushClientId, String cityCode, Instant at) {
        return mapper.registerDevice(accountId, platform, pushClientId, cityCode, at);
    }

    @Override
    public int revokeDevice(UUID accountId, String platform, String pushClientId, Instant at) {
        return mapper.revokeDevice(accountId, platform, pushClientId, at);
    }

    @Override
    public List<PushDeviceRow> activeDevicesOf(UUID accountId, Instant since) {
        return mapper.activeDevicesOf(accountId, since);
    }

    @Override
    public List<PushDeviceRow> activeDevicesPage(String cityCode, UUID afterId, Instant since, int limit) {
        return mapper.activeDevicesPage(cityCode, afterId, since, limit);
    }
}
