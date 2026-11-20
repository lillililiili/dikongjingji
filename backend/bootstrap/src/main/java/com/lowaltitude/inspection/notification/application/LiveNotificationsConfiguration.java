package com.lowaltitude.inspection.notification.application;

import java.time.Instant;
import java.util.UUID;

import com.lowaltitude.inspection.live.application.LivePorts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 直播提醒（M20）：直播模块的提醒端口接到站内信。在开播的事务提交之后写（{@link AfterCommitNotifications}），
 * 同一直播间每个账号只记一条（sourceKey 按直播间和账号）。文字只写直播间标题和主办机构名。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class LiveNotificationsConfiguration {

    static final String LIVE_STARTED = "LIVE_STARTED";
    private static final int MAX_NAME = 40;

    @Bean
    LivePorts.Reminders liveReminders(AfterCommitNotifications notifications) {
        return (accountId, roomId, title, hostName, at) -> notifications.record(
                liveStartedNotice(accountId, roomId, title, hostName, at));
    }

    /** “直播开始了”：鹰眼智能巡检的「机器狗园区夜巡演示」正在直播，点开进直播间。 */
    static NewNotification liveStartedNotice(UUID accountId, UUID roomId, String title, String hostName, Instant at) {
        return new NewNotification(
                accountId,
                NotificationCategory.LIVE_REMINDER,
                LIVE_STARTED,
                "直播开始了",
                clip(hostName, "主办方") + "的「" + clip(title, "直播") + "」正在直播，点开进直播间",
                NotificationTargetType.LIVE_ROOM,
                roomId,
                null,
                "live-reminder:" + roomId + ":" + accountId,
                at);
    }

    private static String clip(String value, String fallback) {
        String name = value == null || value.isBlank() ? fallback : value.strip();
        return name.codePointCount(0, name.length()) <= MAX_NAME
                ? name
                : name.substring(0, name.offsetByCodePoints(0, MAX_NAME));
    }
}
