package com.lowaltitude.inspection.notification.application;

/** 点开通知去的页面。与表上的 CHECK 约束保持一致。 */
public enum NotificationTargetType {
    INSPECTION_PLAN,
    INSPECTION_FULFILLMENT,
    INSPECTION_DELIVERY,
    /** 一份需求（F1）：页面再读它的报价。 */
    DEMAND,
    /** 一个客服会话（G1）：页面再读它的消息。 */
    CONVERSATION
}
