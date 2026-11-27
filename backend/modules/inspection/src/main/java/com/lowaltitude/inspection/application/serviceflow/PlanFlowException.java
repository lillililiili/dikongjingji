package com.lowaltitude.inspection.application.serviceflow;

public class PlanFlowException extends RuntimeException {

    private final PlanFlowError error;

    PlanFlowException(PlanFlowError error) {
        super(error.name());
        this.error = error;
    }

    public PlanFlowError error() {
        return error;
    }

    public enum PlanFlowError {
        INVALID_ARGUMENT,
        FORBIDDEN,
        NOT_FOUND,
        IDEMPOTENCY_CONFLICT,
        VERSION_CONFLICT,
        RESERVATION_STATE_INVALID,
        QUOTE_AMOUNT_MISMATCH,
        PLAN_NOT_PUBLISHED,
        PLAN_EXPIRED,
        PLAN_ALREADY_CONFIRMED,
        ORDER_STATE_INVALID,
        VISIT_STATE_INVALID,
        VISIT_OUT_OF_ORDER,
        DELIVERY_STATE_INVALID,
        DELIVERY_FILES_INVALID,
        /** S4-1：服务商没有登记收款账户，带付款节点的方案不能确认、节点不能付款。 */
        PROVIDER_PAYMENT_UNAVAILABLE,
        /** S4-1：这个节点现在不用付（还没到、已付、服务单已取消）。 */
        PAYMENT_NOT_DUE,
        /** C3：第 1 次巡检已经开始，不能在线取消。 */
        ORDER_NOT_CANCELLABLE,
        /** C3：距开始不足 24 小时、已开始，或已有待处理的改期申请。 */
        RESCHEDULE_NOT_ALLOWED,
        /** C3：改期申请已处理过。 */
        RESCHEDULE_STATE_INVALID
    }
}
