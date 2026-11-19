package com.lowaltitude.inspection.support.application;

// 客服会话和反馈的稳定编码（契约 contracts/openapi-support.yaml），与 V001 迁移里的 CHECK 约束一致。

/** 会话的另一方：PLATFORM 平台客服、MERCHANT 商家、PROVIDER 服务商。 */
enum SupportCounterpartKind {
    PLATFORM,
    MERCHANT,
    PROVIDER
}

/** 消息是哪一边发的：CUSTOMER 客户；COUNTERPART 对方（商家、服务商的操作员，或平台客服）。 */
enum SupportSenderSide {
    CUSTOMER,
    COUNTERPART
}

enum SupportMessageKind {
    TEXT,
    CARD
}

/**
 * 卡片“关于什么”：refId 是对应对象的稳定 ID，页面点开时自己读事实。
 * PRODUCT 商品；MALL_ORDER 商城订单（交易订单）；MALL_INQUIRY 商品询价单；INSPECTION_SERVICE 巡检服务；
 * INSPECTION_ORDER 巡检服务单；PRO_SERVICE 专业服务；ACTIVITY 活动；ACTIVITY_ENROLLMENT 活动报名；VENUE 场馆；
 * VENUE_BOOKING 到馆预约；RENTAL_INQUIRY 租赁询价单；RENTAL_ORDER 租赁订单；DEMAND 需求。
 */
enum SupportCardType {
    PRODUCT,
    MALL_ORDER,
    MALL_INQUIRY,
    INSPECTION_SERVICE,
    INSPECTION_ORDER,
    PRO_SERVICE,
    ACTIVITY,
    ACTIVITY_ENROLLMENT,
    VENUE,
    VENUE_BOOKING,
    RENTAL_INQUIRY,
    RENTAL_ORDER,
    DEMAND
}

/** 反馈类型（原型 Help）：FUNCTION 功能问题、DISPLAY 页面显示、CONTENT 内容有误、SUGGESTION 其他建议。 */
enum SupportFeedbackType {
    FUNCTION,
    DISPLAY,
    CONTENT,
    SUGGESTION
}
