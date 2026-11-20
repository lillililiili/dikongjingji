package com.lowaltitude.inspection.notification.application;

/** 点开通知去的页面。与表上的 CHECK 约束保持一致。 */
public enum NotificationTargetType {
    INSPECTION_PLAN,
    INSPECTION_FULFILLMENT,
    INSPECTION_DELIVERY,
    /** 一份需求（F1）：页面再读它的报价。 */
    DEMAND,
    /** 一个客服会话（G1）：页面再读它的消息。 */
    CONVERSATION,
    /** 一张商品订单（R6-5）：页面再读订单详情。 */
    TRADE_ORDER,
    /** 一张报名单的凭证（R6-5）：id 是报名单。 */
    ACTIVITY_ENROLLMENT,
    /** 一张收费报名的报名订单（R6-5）：id 是报名单，页面看付款和退款。 */
    ACTIVITY_ORDER,
    /** 一张到馆预约（R6-5）：页面再读预约和核销码。 */
    VENUE_BOOKING,
    /** 一个直播间（M20）：页面再读直播间。 */
    LIVE_ROOM
}
