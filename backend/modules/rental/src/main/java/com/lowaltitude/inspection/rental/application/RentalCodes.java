package com.lowaltitude.inspection.rental.application;

// 租赁的稳定编码（契约 contracts/openapi-rental.yaml）。

/** 询价单：PENDING 待报价、QUOTED 待确认、EXPIRED 报价已过期、CONFIRMED 已确认合同、DECLINED 商家拒绝、CLOSED 已关闭。 */
enum RentalInquiryStatus {
    PENDING,
    QUOTED,
    EXPIRED,
    CONFIRMED,
    DECLINED,
    CLOSED
}

/** CUSTOMER 客户关闭；NO_QUOTE 提交 7 天内没有报价。 */
enum RentalCloseReason {
    CUSTOMER,
    NO_QUOTE
}

/** 租赁订单的状态（落库的状态）。 */
enum RentalOrderStatus {
    PENDING_PAYMENT,
    CLOSED,
    AWAITING_HANDOVER,
    HANDOVER_RECORDED,
    IN_USE,
    RETURN_RECORDED,
    ENDED,
    CANCELLED
}

/** 交接方式：商家送达、到店自提。 */
enum RentalHandoverMode {
    DELIVERY("商家送达"),
    PICKUP("到店自提");

    private final String label;

    RentalHandoverMode(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}

/** 归还方式：商家上门取回、送回门店。 */
enum RentalReturnMode {
    COLLECT("商家上门取回"),
    DROP_OFF("送回门店");

    private final String label;

    RentalReturnMode(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}

/** 询价身份：记在客户组织名下，或个人。 */
enum RentalLesseeKind {
    ORGANIZATION,
    PERSONAL
}
