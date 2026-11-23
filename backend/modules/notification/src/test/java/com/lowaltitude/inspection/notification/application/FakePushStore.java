package com.lowaltitude.inspection.notification.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.notification.application.NotificationRows.PushDeviceRow;

/** 测试用：内存里的通知开关和设备。 */
final class FakePushStore implements PushStore {

    record Device(UUID id, UUID accountId, String platform, String clientId, String cityCode, boolean active,
                  Instant reportedAt) {
    }

    final Map<UUID, NotificationPreferences> preferences = new HashMap<>();
    final List<Device> devices = new ArrayList<>();

    @Override
    public Optional<NotificationPreferences> findPreferences(UUID accountId) {
        return Optional.ofNullable(preferences.get(accountId));
    }

    @Override
    public void savePreferences(UUID accountId, NotificationPreferences value, Instant at) {
        preferences.put(accountId, value);
    }

    @Override
    public UUID registerDevice(UUID accountId, String platform, String pushClientId, String cityCode, Instant at) {
        for (int i = 0; i < devices.size(); i++) {
            Device device = devices.get(i);
            if (device.platform().equals(platform) && device.clientId().equals(pushClientId)) {
                devices.set(i, new Device(device.id(), accountId, platform, pushClientId, cityCode, true, at));
                return device.id();
            }
        }
        UUID id = UUID.randomUUID();
        devices.add(new Device(id, accountId, platform, pushClientId, cityCode, true, at));
        return id;
    }

    @Override
    public int revokeDevice(UUID accountId, String platform, String pushClientId, Instant at) {
        for (int i = 0; i < devices.size(); i++) {
            Device device = devices.get(i);
            if (device.active() && device.accountId().equals(accountId) && device.platform().equals(platform)
                    && device.clientId().equals(pushClientId)) {
                devices.set(i, new Device(device.id(), accountId, platform, pushClientId, device.cityCode(), false,
                        device.reportedAt()));
                return 1;
            }
        }
        return 0;
    }

    @Override
    public List<PushDeviceRow> activeDevicesOf(UUID accountId, Instant since) {
        return devices.stream()
                .filter(device -> device.active() && device.accountId().equals(accountId)
                        && device.reportedAt().isAfter(since))
                .map(FakePushStore::row)
                .toList();
    }

    @Override
    public List<PushDeviceRow> activeDevicesPage(String cityCode, UUID afterId, Instant since, int limit) {
        return devices.stream()
                .filter(device -> device.active() && device.reportedAt().isAfter(since))
                .filter(device -> cityCode == null || cityCode.equals(device.cityCode()))
                .sorted(Comparator.comparing(Device::id))
                .filter(device -> afterId == null || device.id().compareTo(afterId) > 0)
                .limit(limit)
                .map(FakePushStore::row)
                .toList();
    }

    private static PushDeviceRow row(Device device) {
        return new PushDeviceRow(device.id(), device.accountId(), device.platform(), device.clientId());
    }
}
