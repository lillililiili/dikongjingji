package com.lowaltitude.inspection.notification.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.notification.application.NotificationRows.PushDeviceRow;
import com.lowaltitude.inspection.notification.application.PushGateway.PushMessage;
import com.lowaltitude.inspection.notification.application.PushGateway.PushPlatform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 站内信写入以后按账号的通知开关推送（{@link NotificationPreferences}）；系统通知发布以后推给收件范围内的设备。
 * 只推给半年内上报过的设备。一台设备推送失败只记日志，接着推下一台。
 */
public final class PushDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);
    static final Duration DEVICE_FRESHNESS = Duration.ofDays(180);
    static final int AUDIENCE_BATCH = 500;

    private final PushGateway gateway;
    private final PushStore store;
    private final Clock clock;

    PushDispatcher(PushGateway gateway, PushStore store, Clock clock) {
        this.gateway = gateway;
        this.store = store;
        this.clock = clock;
    }

    /** 一条刚记下的站内信。开关关着时不推，站内信照样在。返回推给了几台设备。 */
    public int toRecipient(NewNotification notification) {
        NotificationPreferences preferences = store.findPreferences(notification.recipientId())
                .orElse(NotificationPreferences.DEFAULTS);
        if (!preferences.allowsPush(notification.category())) {
            return 0;
        }
        int sent = 0;
        for (PushDeviceRow device : store.activeDevicesOf(notification.recipientId(), since())) {
            if (send(device, notification.category(), notification.title(), notification.body(),
                    notification.targetType(), notification.targetId())) {
                sent++;
            }
        }
        return sent;
    }

    /** 一份刚发布的系统通知：cityCode 为空时推给全部设备，否则只推给最后一次上报在这个城市的设备。 */
    public int toAudience(UUID noticeId, String title, String summary, String cityCode) {
        int sent = 0;
        UUID after = null;
        Instant since = since();
        while (true) {
            List<PushDeviceRow> page = store.activeDevicesPage(cityCode, after, since, AUDIENCE_BATCH);
            for (PushDeviceRow device : page) {
                if (send(device, NotificationCategory.SYSTEM_NOTICE, title, summary,
                        NotificationTargetType.SYSTEM_NOTICE, noticeId)) {
                    sent++;
                }
            }
            if (page.size() < AUDIENCE_BATCH) {
                return sent;
            }
            after = page.get(page.size() - 1).id();
        }
    }

    private boolean send(PushDeviceRow device, NotificationCategory category, String title, String body,
                         NotificationTargetType targetType, UUID targetId) {
        try {
            gateway.send(new PushMessage(device.id(), device.accountId(), PushPlatform.valueOf(device.platform()),
                    device.pushClientId(), category, title, body, targetType, targetId));
            return true;
        } catch (RuntimeException exception) {
            log.warn("push to device {} failed", device.id(), exception);
            return false;
        }
    }

    private Instant since() {
        return clock.instant().minus(DEVICE_FRESHNESS);
    }
}
