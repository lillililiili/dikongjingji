package com.lowaltitude.inspection.application.serviceflow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** 付款尝试、取消、改期和退回整改的读写（S4-1）。服务单、巡检次本身仍由 {@link PlanStore} 读。 */
interface ServiceOrderChangeStore {

    ServiceOrderExtras extras(UUID orderId);

    // ---- 付款 ----

    Optional<NodeRef> findNode(UUID orderId, int nodeNo);

    Optional<NodeRef> findNodeById(UUID nodeId);

    Optional<AttemptRef> findAttempt(UUID attemptId);

    Optional<AttemptRef> findLiveAttempt(UUID nodeId);

    void insertAttempt(AttemptRef attempt, UUID actorId, Instant at);

    boolean markAttemptPaid(UUID attemptId, Instant at);

    boolean markAttemptClosed(UUID attemptId, Instant at);

    boolean markNodePaid(UUID nodeId, UUID tradeOrderId, Instant at);

    boolean markNodeRefunded(UUID nodeId, UUID refundId, Instant at);

    /** 服务单从 expected 改成 next（不比版本，回调里用）；没改到返回 false。 */
    boolean moveOrder(UUID orderId, ServiceOrderStatus expected, ServiceOrderStatus next, String actorType,
                      UUID actorId, String requestId, Instant at);

    boolean cancelOrder(UUID orderId, ServiceOrderStatus expected, int expectedVersion, String reason,
                        UUID actorId, String requestId, Instant at);

    void recordTransition(String aggregateType, UUID aggregateId, String from, String to, String actorType,
                          UUID actorId, String requestId, Instant at);

    // ---- 改期 ----

    Optional<VisitRescheduleRecord> findReschedule(UUID requestId);

    /** 已有待处理的申请时返回 false。 */
    boolean insertReschedule(VisitRescheduleRecord request, UUID orderId, UUID actorId);

    boolean decideReschedule(UUID requestId, RescheduleStatus status, String note, UUID actorId, Instant at);

    boolean rescheduleVisit(UUID visitId, int expectedVersion, LocalDate plannedDate, UUID actorId, Instant at);

    // ---- 退回整改 ----

    int deliveryRevision(UUID deliveryId);

    boolean returnForRework(StoredServiceOrder order, ServiceOrderStatus nextOrderStatus, StoredServiceVisit visit,
                            StoredDelivery delivery, VisitReworkRecord rework, UUID actorId, String requestId);
}

/** 订单上的一个付款节点。 */
record NodeRef(
        UUID id,
        UUID orderId,
        int nodeNo,
        PaymentNodeType nodeType,
        Integer visitNo,
        UUID visitId,
        long amountMinor,
        Instant paidAt,
        UUID paidTradeOrderId,
        UUID refundId
) {
}

/** 一次付款尝试。 */
record AttemptRef(
        UUID id,
        UUID orderId,
        UUID nodeId,
        UUID tradeOrderId,
        long amountMinor,
        PaymentAttemptStatus status,
        Instant payBefore
) {
}
