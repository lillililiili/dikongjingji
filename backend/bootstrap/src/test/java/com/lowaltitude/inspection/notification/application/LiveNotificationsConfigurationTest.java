package com.lowaltitude.inspection.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionOperations;

/** 直播提醒（M20）：开播的事务提交后给设了提醒的账号记一条“直播开始了”，同一直播间每人只记一条。 */
class LiveNotificationsConfigurationTest {

    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOM = UUID.fromString("f4000000-0000-4000-8000-000000000003");
    private static final Instant AT = Instant.parse("2026-11-22T11:30:00Z");

    @Test
    void theNoticeNamesTheRoomAndTheHostAndOpensTheRoom() {
        NewNotification notice = LiveNotificationsConfiguration.liveStartedNotice(ACCOUNT, ROOM, "编程机器人入门",
                "示例机器人商家", AT);

        assertThat(notice).isEqualTo(new NewNotification(ACCOUNT, NotificationCategory.LIVE_REMINDER, "LIVE_STARTED",
                "直播开始了", "示例机器人商家的「编程机器人入门」正在直播，点开进直播间", NotificationTargetType.LIVE_ROOM,
                ROOM, null, "live-reminder:" + ROOM + ":" + ACCOUNT, AT));
        assertThat(LiveNotificationsConfiguration.LIVE_STARTED).isEqualTo("LIVE_STARTED");
        // 名字太长时截到 40 个字，空的写“主办方”。
        assertThat(LiveNotificationsConfiguration.liveStartedNotice(ACCOUNT, ROOM, "一".repeat(60), " ", AT).body())
                .isEqualTo("主办方的「" + "一".repeat(40) + "」正在直播，点开进直播间");
    }

    @Test
    void theNoticeIsWrittenOnlyAfterTheGoLiveTransactionCommits() {
        NotificationService notifications = mock(NotificationService.class);
        var reminders = new LiveNotificationsConfiguration().liveReminders(
                new AfterCommitNotifications(notifications, TransactionOperations.withoutTransaction()));

        TransactionSynchronizationManager.initSynchronization();
        try {
            reminders.liveStarted(ACCOUNT, ROOM, "编程机器人入门", "示例机器人商家", AT);
            verify(notifications, never()).record(any());
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        verify(notifications).record(LiveNotificationsConfiguration.liveStartedNotice(ACCOUNT, ROOM, "编程机器人入门",
                "示例机器人商家", AT));
    }
}
