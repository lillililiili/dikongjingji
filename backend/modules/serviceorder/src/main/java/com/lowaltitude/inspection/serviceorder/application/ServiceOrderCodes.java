package com.lowaltitude.inspection.serviceorder.application;

/** 服务订单的稳定编码（契约 contracts/openapi-service-order.yaml 1.0.0 的枚举）。 */
public final class ServiceOrderCodes {

    private ServiceOrderCodes() {
    }

    public enum ServiceOrderStatus {
        AWAITING_PLAN,
        PLAN_PENDING,
        AWAITING_PAYMENT,
        AWAITING_START,
        IN_SERVICE,
        DELIVERED,
        REWORK,
        COMPLETED,
        CANCELLED,
        TERMINATED;

        /** 开工之前：客户或服务商都能取消。 */
        boolean beforeStart() {
            return this == AWAITING_PLAN || this == PLAN_PENDING || this == AWAITING_PAYMENT || this == AWAITING_START;
        }

        /** 开工之后、还没结束：只能协商终止。 */
        boolean inService() {
            return this == IN_SERVICE || this == DELIVERED || this == REWORK;
        }
    }

    /** 和需求类型同名；巡检不生成服务订单。 */
    public enum ServiceOrderType {
        AERIAL,
        SURVEY,
        MAINTENANCE,
        TECH_SUPPORT
    }

    public enum ServiceOrderCustomerKind {
        ORGANIZATION,
        PERSONAL
    }

    public enum ServiceOrderParty {
        CUSTOMER,
        PROVIDER
    }

    public enum ServiceOrderDeliveryStatus {
        SUBMITTED,
        ACCEPTED,
        RETURNED
    }

    public enum ServiceOrderRefundKind {
        CANCEL,
        TERMINATE
    }

    public enum ServiceOrderEventType {
        CREATED,
        PLAN_ISSUED,
        PLAN_REVISED,
        PLAN_CONFIRMED,
        PAID,
        STARTED,
        DELIVERED,
        ACCEPTED,
        RETURNED,
        CANCELLED,
        TERMINATED
    }

    /** 付款尝试的状态（不出现在接口里）。 */
    enum PaymentStatus {
        PENDING,
        PAID,
        CLOSED
    }
}
