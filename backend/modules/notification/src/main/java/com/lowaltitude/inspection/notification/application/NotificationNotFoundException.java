package com.lowaltitude.inspection.notification.application;

/** 通知不存在，或者不是当前账号的。两种情况对调用方没有区别。 */
public final class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException() {
        super("notification was not found");
    }
}
