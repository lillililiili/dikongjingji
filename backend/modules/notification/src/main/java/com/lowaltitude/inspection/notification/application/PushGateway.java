package com.lowaltitude.inspection.notification.application;

import java.util.UUID;

/**
 * 推送服务（个推、厂商通道等）的端口。服务商还没定（docs/redesign/08-后续开发计划.md §5“推送”），
 * 开发环境是本机模拟实现（FakePushGateway：记日志和开发表），其他环境不推送（DisabledPushGateway）。
 * 推送只是提醒：发不出去只记日志，不影响站内信和业务。
 */
public interface PushGateway {

    /** 发给一台设备。失败抛异常，由调用方记日志。 */
    void send(PushMessage message);

    /**
     * 一条推送。只带标题、正文和跳转用的稳定 ID，和站内信一样不放地址、手机号、核销码等敏感内容。
     * clientId 是推送服务给设备的标识，日志里不要整段打出来。
     */
    record PushMessage(
            UUID deviceId,
            UUID accountId,
            PushPlatform platform,
            String clientId,
            NotificationCategory category,
            String title,
            String body,
            NotificationTargetType targetType,
            UUID targetId
    ) {
    }

    enum PushPlatform {
        ANDROID,
        IOS
    }
}
