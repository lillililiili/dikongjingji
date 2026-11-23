package com.lowaltitude.inspection.notification.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.notification.application.NotificationRows.PushDeviceRow;

/** 通知偏好和设备推送标识的存取（S5-9）。 */
interface PushStore {

    Optional<NotificationPreferences> findPreferences(UUID accountId);

    void savePreferences(UUID accountId, NotificationPreferences preferences, Instant at);

    UUID registerDevice(UUID accountId, String platform, String pushClientId, String cityCode, Instant at);

    int revokeDevice(UUID accountId, String platform, String pushClientId, Instant at);

    List<PushDeviceRow> activeDevicesOf(UUID accountId, Instant since);

    /** cityCode 为空时是全部设备；afterId 为空时从头开始。 */
    List<PushDeviceRow> activeDevicesPage(String cityCode, UUID afterId, Instant since, int limit);
}
