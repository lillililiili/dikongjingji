package com.lowaltitude.inspection.notification.application;

/** 通知分类。与表上的 CHECK 约束保持一致。 */
public enum NotificationCategory {
    SERVICE_PROGRESS,
    /** 需求与报价（F1）：收到报价、需求关闭。 */
    DEMAND_QUOTE,
    /** 客服消息（G1）：商家、服务商或平台客服在会话里回复了你。每个会话最多一条未读。 */
    CUSTOMER_SERVICE,
    /** 交易物流（R6-5）：商城商品订单已发货、可以自提了、退款已原路退回。 */
    TRADE_LOGISTICS,
    /** 活动提醒（R6-5）：报名成功、报名已退款、到馆预约成功、到馆已改期。 */
    ACTIVITY_REMINDER,
    /** 直播提醒（M20）：设了提醒的直播开始了。同一直播间每个账号只记一条。 */
    LIVE_REMINDER
}
