package com.lowaltitude.inspection.notification.application;

import java.time.Clock;
import java.util.UUID;

/** 账号自己的通知开关（契约 contracts/openapi-notification.yaml 1.5.0）。规则见 {@link NotificationPreferences}。 */
public class NotificationPreferenceService {

    private final PushStore store;
    private final Clock clock;

    NotificationPreferenceService(PushStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public NotificationPreferences read(UUID accountId) {
        requireAccount(accountId);
        return store.findPreferences(accountId).orElse(NotificationPreferences.DEFAULTS);
    }

    /** 只改给了值的开关；一个都没给是参数错误。 */
    public NotificationPreferences update(UUID accountId, Boolean orderServicePush, Boolean activityPush,
                                          Boolean discoverDot) {
        requireAccount(accountId);
        if (orderServicePush == null && activityPush == null && discoverDot == null) {
            throw new IllegalArgumentException("nothing to change");
        }
        NotificationPreferences next = read(accountId).with(orderServicePush, activityPush, discoverDot);
        store.savePreferences(accountId, next, clock.instant());
        return next;
    }

    private static void requireAccount(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("account is required");
        }
    }
}
