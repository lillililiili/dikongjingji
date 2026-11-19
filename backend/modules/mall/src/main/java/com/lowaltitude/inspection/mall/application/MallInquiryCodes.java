package com.lowaltitude.inspection.mall.application;

// 询价商品的稳定编码（契约 contracts/openapi-mall-inquiry.yaml）。

/** 询价单：OPEN 待报价、QUOTED 报价中、CHOSEN 已选定、CLOSED 已关闭。 */
enum MallInquiryStatus {
    OPEN,
    QUOTED,
    CHOSEN,
    CLOSED
}

/** CUSTOMER 客户关闭；NO_QUOTES 截止时没有任何报价；QUOTES_LAPSED 截止后报价都已失效还没有选。 */
enum MallInquiryCloseReason {
    CUSTOMER,
    NO_QUOTES,
    QUOTES_LAPSED
}

/** 发给一家商家的状态：INVITED 待报价、QUOTED 已报价、DECLINED 已拒绝。 */
enum MallInquiryInvitationStatus {
    INVITED,
    QUOTED,
    DECLINED
}

/**
 * 一份报价（每家最新一版）的状态：ACTIVE 有效、EXPIRED 过了有效期、WITHDRAWN 商家拒绝时撤回、CHOSEN 被选中、
 * LAPSED 选了别家或询价单关闭后失效。被新一版取代的旧版（SUPERSEDED）不对外。
 */
enum MallInquiryQuoteStatus {
    ACTIVE,
    EXPIRED,
    WITHDRAWN,
    CHOSEN,
    LAPSED
}

/** 比选排序：ALL 综合（按报价先后）、PRICE 总价从低到高、LEAD_TIME 交期从短到长；相同时按报价先后。 */
enum MallInquirySort {
    ALL,
    PRICE,
    LEAD_TIME
}

enum MallInquiryBuyerKind {
    ORGANIZATION,
    PERSONAL
}
