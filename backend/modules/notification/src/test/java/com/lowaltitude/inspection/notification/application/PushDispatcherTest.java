package com.lowaltitude.inspection.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.notification.application.PushGateway.PushMessage;
import org.junit.jupiter.api.Test;

/** S5-9：站内信写入后按通知开关推送；系统通知按收件范围推；坏掉的一台不影响别的。 */
class PushDispatcherTest {

    private static final UUID ME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOMEONE_ELSE = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID TARGET = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-22T04:00:00Z");

    private final FakePushStore store = new FakePushStore();
    private final List<PushMessage> sent = new ArrayList<>();
    private final PushDispatcher dispatcher = new PushDispatcher(sent::add, store, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void pushesToTheRecipientsRecentDevicesWhenTheSwitchIsOn() {
        store.registerDevice(ME, "ANDROID", "cid-android", "370500", NOW.minusSeconds(60));
        store.registerDevice(ME, "IOS", "cid-ios", null, NOW.minusSeconds(60));
        // 半年没上报的设备不推。
        store.registerDevice(ME, "ANDROID", "cid-old", null, NOW.minus(PushDispatcher.DEVICE_FRESHNESS).minusSeconds(1));
        store.registerDevice(SOMEONE_ELSE, "ANDROID", "cid-other", null, NOW);

        assertThat(dispatcher.toRecipient(notification(NotificationCategory.TRADE_LOGISTICS))).isEqualTo(2);
        assertThat(sent).extracting(PushMessage::clientId).containsExactlyInAnyOrder("cid-android", "cid-ios");
        assertThat(sent).allSatisfy(message -> {
            assertThat(message.accountId()).isEqualTo(ME);
            assertThat(message.title()).isEqualTo("订单已发货");
            assertThat(message.targetType()).isEqualTo(NotificationTargetType.TRADE_ORDER);
            assertThat(message.targetId()).isEqualTo(TARGET);
        });
    }

    @Test
    void aSwitchedOffCategoryIsNotPushedButOthersStillAre() {
        store.registerDevice(ME, "ANDROID", "cid-android", null, NOW);
        store.savePreferences(ME, new NotificationPreferences(false, true, true), NOW);

        assertThat(dispatcher.toRecipient(notification(NotificationCategory.SERVICE_PROGRESS))).isZero();
        assertThat(dispatcher.toRecipient(notification(NotificationCategory.CUSTOMER_SERVICE))).isZero();
        assertThat(dispatcher.toRecipient(notification(NotificationCategory.ACTIVITY_REMINDER))).isEqualTo(1);

        store.savePreferences(ME, new NotificationPreferences(true, false, true), NOW);
        assertThat(dispatcher.toRecipient(notification(NotificationCategory.LIVE_REMINDER))).isZero();
        assertThat(dispatcher.toRecipient(notification(NotificationCategory.DEMAND_QUOTE))).isEqualTo(1);
    }

    @Test
    void systemNoticesGoToEveryDeviceOrTheCitysDevicesInBatches() {
        for (int i = 0; i < PushDispatcher.AUDIENCE_BATCH + 3; i++) {
            store.registerDevice(UUID.randomUUID(), "ANDROID", "cid-" + i, i % 2 == 0 ? "370500" : "370100", NOW);
        }

        assertThat(dispatcher.toAudience(TARGET, "隐私政策更新摘要", "查看主要变化与生效日期", null))
                .isEqualTo(PushDispatcher.AUDIENCE_BATCH + 3);
        assertThat(sent).extracting(PushMessage::clientId).doesNotHaveDuplicates();
        assertThat(sent).allSatisfy(message -> {
            assertThat(message.category()).isEqualTo(NotificationCategory.SYSTEM_NOTICE);
            assertThat(message.targetType()).isEqualTo(NotificationTargetType.SYSTEM_NOTICE);
        });
        sent.clear();
        assertThat(dispatcher.toAudience(TARGET, "东营站公告", "摘要", "370100")).isEqualTo((PushDispatcher.AUDIENCE_BATCH + 3) / 2);
    }

    // 系统通知没有开关：账号把两个推送开关都关了也照样推。
    @Test
    void systemNoticesIgnoreTheSwitches() {
        store.registerDevice(ME, "IOS", "cid-ios", null, NOW);
        store.savePreferences(ME, new NotificationPreferences(false, false, false), NOW);

        assertThat(dispatcher.toAudience(TARGET, "隐私政策更新摘要", "查看主要变化与生效日期", null)).isEqualTo(1);
    }

    @Test
    void aFailingDeviceDoesNotStopTheOthers() {
        store.registerDevice(ME, "ANDROID", "cid-broken", null, NOW);
        store.registerDevice(ME, "IOS", "cid-ios", null, NOW);
        PushDispatcher flaky = new PushDispatcher(message -> {
            if (message.clientId().equals("cid-broken")) {
                throw new IllegalStateException("push service is down");
            }
            sent.add(message);
        }, store, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(flaky.toRecipient(notification(NotificationCategory.SERVICE_PROGRESS))).isEqualTo(1);
        assertThat(sent).extracting(PushMessage::clientId).containsExactly("cid-ios");
    }

    private static NewNotification notification(NotificationCategory category) {
        return new NewNotification(ME, category, "TRADE_ORDER_SHIPPED", "订单已发货", "编程机器人探索套装已发出",
                NotificationTargetType.TRADE_ORDER, TARGET, null, "k-" + category, NOW);
    }
}
