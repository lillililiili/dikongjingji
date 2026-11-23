package com.lowaltitude.inspection.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PushDeviceAndPreferenceServiceTest {

    private static final UUID ME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOMEONE_ELSE = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final Instant NOW = Instant.parse("2026-11-22T04:00:00Z");

    private final FakePushStore store = new FakePushStore();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final PushDeviceService devices = new PushDeviceService(store, clock);
    private final NotificationPreferenceService preferences = new NotificationPreferenceService(store, clock);

    @Test
    void preferencesStartAllOnAndChangeOnlyTheGivenSwitches() {
        assertThat(preferences.read(ME)).isEqualTo(NotificationPreferences.DEFAULTS);

        assertThat(preferences.update(ME, null, false, null)).isEqualTo(new NotificationPreferences(true, false, true));
        assertThat(preferences.update(ME, false, null, null)).isEqualTo(new NotificationPreferences(false, false, true));
        assertThat(preferences.read(ME)).isEqualTo(new NotificationPreferences(false, false, true));
        assertThat(preferences.read(SOMEONE_ELSE)).isEqualTo(NotificationPreferences.DEFAULTS);
        assertThatThrownBy(() -> preferences.update(ME, null, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preferences.read(null)).isInstanceOf(IllegalArgumentException.class);
    }

    // 开关和分类的对应：订单与服务进度四类、活动提醒两类，系统通知一直推。
    @Test
    void switchesCoverTheirCategories() {
        NotificationPreferences off = new NotificationPreferences(false, false, true);
        for (NotificationCategory category : NotificationCategory.values()) {
            assertThat(off.allowsPush(category)).as(category.name())
                    .isEqualTo(category == NotificationCategory.SYSTEM_NOTICE);
            assertThat(NotificationPreferences.DEFAULTS.allowsPush(category)).isTrue();
        }
        NotificationPreferences activityOnly = new NotificationPreferences(false, true, false);
        assertThat(activityOnly.allowsPush(NotificationCategory.ACTIVITY_REMINDER)).isTrue();
        assertThat(activityOnly.allowsPush(NotificationCategory.LIVE_REMINDER)).isTrue();
        assertThat(activityOnly.allowsPush(NotificationCategory.TRADE_LOGISTICS)).isFalse();
    }

    @Test
    void aDeviceIsOneRowThatFollowsTheLastAccountToReportIt() {
        UUID first = devices.register(ME, "android", "cid-1", "370500").deviceId();
        UUID again = devices.register(SOMEONE_ELSE, "ANDROID", "cid-1", null).deviceId();

        assertThat(again).isEqualTo(first);
        assertThat(store.devices).singleElement().satisfies(device -> {
            assertThat(device.accountId()).isEqualTo(SOMEONE_ELSE);
            assertThat(device.cityCode()).isNull();
        });
        // 只能撤自己名下的。
        assertThat(devices.unregister(ME, "ANDROID", "cid-1")).isFalse();
        assertThat(devices.unregister(SOMEONE_ELSE, "ANDROID", "cid-1")).isTrue();
        assertThat(devices.unregister(SOMEONE_ELSE, "ANDROID", "cid-1")).isFalse();
    }

    @Test
    void rejectsAMalformedDevice() {
        assertThatThrownBy(() -> devices.register(ME, "HARMONY", "cid", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> devices.register(ME, null, "cid", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> devices.register(ME, "IOS", "", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> devices.register(ME, "IOS", "has space", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> devices.register(ME, "IOS", "c".repeat(201), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> devices.register(ME, "IOS", "cid", "dongying"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> devices.register(null, "IOS", "cid", null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(devices.register(ME, "IOS", "c".repeat(200), null).platform()).isEqualTo(PushGateway.PushPlatform.IOS);
    }
}
