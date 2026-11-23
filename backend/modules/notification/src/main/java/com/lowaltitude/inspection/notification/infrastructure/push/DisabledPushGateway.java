package com.lowaltitude.inspection.notification.infrastructure.push;

import com.lowaltitude.inspection.notification.application.PushGateway;

/** 推送服务商未定时（dev 以外的环境）：什么都不发。站内信照常记录。 */
public final class DisabledPushGateway implements PushGateway {

    @Override
    public void send(PushMessage message) {
        // 没有可用的推送服务。
    }
}
