package com.lowaltitude.inspection.application.serviceflow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.infrastructure.persistence.InspectionPaymentMapper;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionPaymentMapper.PaymentAttemptRow;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionPaymentMapper.PaymentNodeRow;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionPaymentMapper.RescheduleRow;
import com.lowaltitude.inspection.infrastructure.persistence.ServiceFlowPersistenceMapper;

final class PostgresServiceOrderChangeStore implements ServiceOrderChangeStore {

    private final InspectionPaymentMapper mapper;
    private final ServiceFlowPersistenceMapper flow;
    // 退款进度在交易模块；没有接交易时只显示已申请。
    private final Supplier<InspectionPaymentPort> payments;

    PostgresServiceOrderChangeStore(
            InspectionPaymentMapper mapper,
            ServiceFlowPersistenceMapper flow,
            Supplier<InspectionPaymentPort> payments
    ) {
        this.mapper = mapper;
        this.flow = flow;
        this.payments = payments;
    }

    @Override
    public ServiceOrderExtras extras(UUID orderId) {
        List<PaymentAttemptRecord> attempts = mapper.findAttemptsByOrder(orderId).stream()
                .map(PostgresServiceOrderChangeStore::toRecord)
                .toList();
        List<InspectionPaymentMapper.NodeRefundRow> refundRows = mapper.findNodeRefunds(orderId);
        Map<UUID, InspectionPaymentPort.RefundProgress> progress = refundRows.isEmpty()
                ? Map.of()
                : payments.get().refundStates(refundRows.stream()
                        .map(InspectionPaymentMapper.NodeRefundRow::refundId)
                        .toList());
        List<NodeRefundRecord> refunds = refundRows.stream()
                .map(row -> {
                    InspectionPaymentPort.RefundProgress state = progress.get(row.refundId());
                    return new NodeRefundRecord(
                            row.nodeNo(),
                            row.refundId(),
                            state == null ? row.amountMinor() : state.amountMinor(),
                            state == null ? null : state.status(),
                            row.refundRequestedAt());
                })
                .toList();
        List<VisitRescheduleRecord> reschedules = mapper.findReschedulesByOrder(orderId).stream()
                .map(PostgresServiceOrderChangeStore::toRecord)
                .toList();
        List<VisitReworkRecord> reworks = mapper.findReworksByOrder(orderId).stream()
                .map(row -> new VisitReworkRecord(
                        row.id(),
                        row.visitId(),
                        row.deliveryRevision(),
                        row.reason(),
                        row.requestedAt(),
                        row.resolvedAt()))
                .toList();
        Map<UUID, LocalDate> originals = mapper.findOriginalPlannedDates(orderId).stream()
                .collect(Collectors.toMap(
                        InspectionPaymentMapper.OriginalDateRow::visitId,
                        InspectionPaymentMapper.OriginalDateRow::originalPlannedDate));
        OrderCancellation cancellation = mapper.findOrderState(orderId)
                .filter(row -> row.cancelledAt() != null)
                .map(row -> new OrderCancellation(row.cancelledAt(), row.cancelReason()))
                .orElse(null);
        return new ServiceOrderExtras(attempts, refunds, reschedules, reworks, originals, cancellation);
    }

    @Override
    public Optional<NodeRef> findNode(UUID orderId, int nodeNo) {
        return mapper.findNode(orderId, nodeNo).map(PostgresServiceOrderChangeStore::toRef);
    }

    @Override
    public Optional<NodeRef> findNodeById(UUID nodeId) {
        return mapper.findNodeById(nodeId).map(PostgresServiceOrderChangeStore::toRef);
    }

    @Override
    public Optional<AttemptRef> findAttempt(UUID attemptId) {
        return mapper.findAttempt(attemptId).map(PostgresServiceOrderChangeStore::toRef);
    }

    @Override
    public Optional<AttemptRef> findLiveAttempt(UUID nodeId) {
        return mapper.findLiveAttempt(nodeId).map(PostgresServiceOrderChangeStore::toRef);
    }

    @Override
    public void insertAttempt(AttemptRef attempt, UUID actorId, Instant at) {
        mapper.insertAttempt(
                attempt.id(),
                attempt.orderId(),
                attempt.nodeId(),
                attempt.tradeOrderId(),
                attempt.amountMinor(),
                attempt.payBefore(),
                actorId,
                at);
    }

    @Override
    public boolean markAttemptPaid(UUID attemptId, Instant at) {
        return mapper.markAttemptPaid(attemptId, at) == 1;
    }

    @Override
    public boolean markAttemptClosed(UUID attemptId, Instant at) {
        return mapper.markAttemptClosed(attemptId, at) == 1;
    }

    @Override
    public boolean markNodePaid(UUID nodeId, UUID tradeOrderId, Instant at) {
        return mapper.markNodePaid(nodeId, tradeOrderId, at) == 1;
    }

    @Override
    public boolean markNodeRefunded(UUID nodeId, UUID refundId, Instant at) {
        return mapper.markNodeRefunded(nodeId, refundId, at) == 1;
    }

    @Override
    public boolean moveOrder(UUID orderId, ServiceOrderStatus expected, ServiceOrderStatus next, String actorType,
                             UUID actorId, String requestId, Instant at) {
        if (mapper.updateOrderStatus(orderId, expected.name(), next.name(), actorId, at) != 1) {
            return false;
        }
        recordTransition("SERVICE_ORDER", orderId, expected.name(), next.name(), actorType, actorId, requestId, at);
        return true;
    }

    @Override
    public boolean cancelOrder(UUID orderId, ServiceOrderStatus expected, int expectedVersion, String reason,
                               UUID actorId, String requestId, Instant at) {
        if (mapper.cancelOrder(orderId, expected.name(), expectedVersion, reason, actorId, at) != 1) {
            return false;
        }
        recordTransition("SERVICE_ORDER", orderId, expected.name(), ServiceOrderStatus.CANCELLED.name(),
                "CUSTOMER", actorId, requestId, at);
        flow.insertOutbox("SERVICE_ORDER", orderId, "INSPECTION_ORDER_CANCELLED", at);
        return true;
    }

    @Override
    public void recordTransition(String aggregateType, UUID aggregateId, String from, String to, String actorType,
                                 UUID actorId, String requestId, Instant at) {
        flow.insertTransition(aggregateType, aggregateId, from, to, actorType, actorId, requestId, at);
    }

    @Override
    public Optional<VisitRescheduleRecord> findReschedule(UUID requestId) {
        return mapper.findReschedule(requestId).map(PostgresServiceOrderChangeStore::toRecord);
    }

    @Override
    public boolean insertReschedule(VisitRescheduleRecord request, UUID orderId, UUID actorId) {
        return mapper.insertReschedule(
                request.id(),
                orderId,
                request.visitId(),
                request.fromDate(),
                request.requestedDate(),
                request.reason(),
                actorId,
                request.requestedAt()) == 1;
    }

    @Override
    public boolean decideReschedule(UUID requestId, RescheduleStatus status, String note, UUID actorId, Instant at) {
        return mapper.decideReschedule(requestId, status.name(), note, actorId, at) == 1;
    }

    @Override
    public boolean rescheduleVisit(UUID visitId, int expectedVersion, LocalDate plannedDate, UUID actorId,
                                   Instant at) {
        return mapper.rescheduleVisit(visitId, expectedVersion, plannedDate, actorId, at) == 1;
    }

    @Override
    public int deliveryRevision(UUID deliveryId) {
        return mapper.findDeliveryRevision(deliveryId).orElse(1);
    }

    @Override
    public boolean returnForRework(StoredServiceOrder order, ServiceOrderStatus nextOrderStatus,
                                   StoredServiceVisit visit, StoredDelivery delivery, VisitReworkRecord rework,
                                   UUID actorId, String requestId) {
        Instant at = rework.requestedAt();
        if (mapper.returnVisitForRework(visit.id(), visit.version(), actorId, at) != 1) {
            return false;
        }
        if (mapper.returnDelivery(delivery.id(), delivery.version(), at) != 1) {
            throw new IllegalStateException("delivery changed while its visit was locked");
        }
        if (order.status() != nextOrderStatus
                && !moveOrder(order.id(), order.status(), nextOrderStatus, "CUSTOMER", actorId, requestId, at)) {
            throw new IllegalStateException("service order changed while it was locked");
        }
        mapper.insertRework(rework.id(), order.id(), visit.id(), delivery.id(), rework.deliveryRevision(),
                rework.reason(), actorId, at);
        recordTransition("SERVICE_VISIT", visit.id(), visit.status().name(), ServiceVisitStatus.REWORK.name(),
                "CUSTOMER", actorId, requestId, at);
        recordTransition("DELIVERY", delivery.id(), delivery.status().name(), DeliveryStatus.RETURNED.name(),
                "CUSTOMER", actorId, requestId, at);
        flow.insertOutbox("SERVICE_VISIT", visit.id(), "INSPECTION_VISIT_REWORK_REQUESTED", at);
        return true;
    }

    private static NodeRef toRef(PaymentNodeRow row) {
        return new NodeRef(
                row.id(),
                row.serviceOrderId(),
                row.nodeNo(),
                PaymentNodeType.valueOf(row.nodeType()),
                row.visitNo(),
                row.visitId(),
                row.amountMinor(),
                row.paidAt(),
                row.tradePaymentId(),
                row.refundId());
    }

    private static AttemptRef toRef(PaymentAttemptRow row) {
        return new AttemptRef(
                row.id(),
                row.serviceOrderId(),
                row.nodeId(),
                row.tradeOrderId(),
                row.amountMinor(),
                PaymentAttemptStatus.valueOf(row.status()),
                row.payBefore());
    }

    private static PaymentAttemptRecord toRecord(PaymentAttemptRow row) {
        return new PaymentAttemptRecord(
                row.id(),
                row.nodeNo(),
                PaymentNodeType.valueOf(row.nodeType()),
                row.visitNo(),
                row.tradeOrderId(),
                row.amountMinor(),
                PaymentAttemptStatus.valueOf(row.status()),
                row.payBefore(),
                row.createdAt(),
                row.paidAt(),
                row.closedAt());
    }

    private static VisitRescheduleRecord toRecord(RescheduleRow row) {
        return new VisitRescheduleRecord(
                row.id(),
                row.visitId(),
                row.fromDate(),
                row.requestedDate(),
                row.reason(),
                RescheduleStatus.valueOf(row.status()),
                row.decisionNote(),
                row.requestedAt(),
                row.decidedAt());
    }
}
