package com.lowaltitude.inspection.notification.application;

/** 通知分类。与表上的 CHECK 约束保持一致。 */
public enum NotificationCategory {
    SERVICE_PROGRESS,
    /** 需求与报价（F1）：收到报价、需求关闭。 */
    DEMAND_QUOTE
}
