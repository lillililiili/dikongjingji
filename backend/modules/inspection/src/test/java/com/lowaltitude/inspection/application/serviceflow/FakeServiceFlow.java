package com.lowaltitude.inspection.application.serviceflow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;

/**
 * 内存里的服务单、付款节点、付款尝试、改期和整改（S4-1 的单元测试用）。改状态时同时改 {@link #orders} 里的服务单，
 * 和数据库里一样：服务单、巡检次、节点读出来就是最新的。
 */
final class FakeServiceFlow implements PlanStore, ServiceOrderChangeStore, DeliveryStore {

    static final InTransaction DIRECT = new InTransaction() {
        @Override
        public <T> T call(Supplier<T> work) {
            return work.get();
        }
    };

    final Map<UUID, StoredServiceOrder> orders = new HashMap<>();
    final Map<UUID, NodeRef> nodes = new LinkedHashMap<>();
    final Map<UUID, AttemptRef> attempts = new LinkedHashMap<>();
    final Map<UUID, Instant> attemptCreated = new HashMap<>();
    final Map<UUID, VisitRescheduleRecord> reschedules = new LinkedHashMap<>();
    final Map<UUID, VisitReworkRecord> reworks = new LinkedHashMap<>();
    final Map<UUID, LocalDate> originals = new HashMap<>();
    final Map<UUID, OrderCancellation> cancellations = new HashMap<>();
    final Map<UUID, StoredDelivery> deliveries = new HashMap<>();
    final List<String> orderMoves = new ArrayList<>();
    final List<String> transitions = new ArrayList<>();

    /** 建一张服务单：首付金额（为 0 时不建首付节点）和每次巡检的服务费，计划日期从后天起每周一次。 */
    UUID order(ServiceOrderStatus status, long downPayment, List<Long> visitFees) {
        UUID orderId = UUID.randomUUID();
        List<StoredServiceVisit> visits = new ArrayList<>();
        for (int index = 0; index < visitFees.size(); index++) {
            visits.add(new StoredServiceVisit(UUID.randomUUID(), orderId, index + 1,
                    InspectionPaymentServiceTest.day(2 + 7 * index), null, ServiceVisitStatus.PENDING_SCHEDULE,
                    List.of(), null, null, null, 0));
        }
        List<OrderPaymentNode> paymentNodes = new ArrayList<>();
        int nodeNo = 1;
        paymentNodes.add(new OrderPaymentNode(nodeNo++, PaymentNodeType.DOWN_PAYMENT, null, null, downPayment,
                downPayment == 0 ? InspectionPaymentServiceTest.NOW : null));
        for (int index = 0; index < visitFees.size(); index++) {
            paymentNodes.add(new OrderPaymentNode(nodeNo++, PaymentNodeType.PER_VISIT, index + 1,
                    visits.get(index).id(), visitFees.get(index), null));
        }
        for (OrderPaymentNode node : paymentNodes) {
            UUID nodeId = UUID.randomUUID();
            nodes.put(nodeId, new NodeRef(nodeId, orderId, node.nodeNo(), node.nodeType(), node.visitNo(),
                    node.visitId(), node.amountMinor(), node.paidAt(), null, null));
        }
        orders.put(orderId, build(orderId, status, visits, paymentNodes, 0));
        return orderId;
    }

    void payNode(UUID orderId, int nodeNo) {
        NodeRef node = findNode(orderId, nodeNo).orElseThrow();
        markNodePaid(node.id(), UUID.randomUUID(), InspectionPaymentServiceTest.NOW);
    }

    void acceptVisit(UUID orderId, int visitNo) {
        updateVisit(orderId, visitNo, visit -> new StoredServiceVisit(visit.id(), visit.serviceOrderId(),
                visit.visitNo(), visit.plannedDate(), visit.plannedStartAt(), ServiceVisitStatus.ACCEPTED,
                visit.progress(), InspectionPaymentServiceTest.NOW, InspectionPaymentServiceTest.NOW,
                InspectionPaymentServiceTest.NOW, visit.version() + 1));
    }

    void startVisit(UUID orderId, int visitNo) {
        updateVisit(orderId, visitNo, visit -> new StoredServiceVisit(visit.id(), visit.serviceOrderId(),
                visit.visitNo(), visit.plannedDate(), visit.plannedStartAt(), ServiceVisitStatus.IN_PROGRESS,
                visit.progress(), InspectionPaymentServiceTest.NOW, null, null, visit.version() + 1));
    }

    void deliverVisit(UUID orderId, int visitNo) {
        StoredServiceVisit visit = updateVisit(orderId, visitNo, current -> new StoredServiceVisit(current.id(),
                current.serviceOrderId(), current.visitNo(), current.plannedDate(), current.plannedStartAt(),
                ServiceVisitStatus.DELIVERED, current.progress(), InspectionPaymentServiceTest.NOW,
                InspectionPaymentServiceTest.NOW, null, current.version() + 1));
        UUID deliveryId = UUID.randomUUID();
        deliveries.put(visit.id(), new StoredDelivery(deliveryId, orderId, visit.id(), DeliveryStatus.PUBLISHED,
                null, null, List.of(), 0, IssueGroupBy.SEVERITY, List.of(), List.of(),
                InspectionPaymentServiceTest.NOW, null, 0));
    }

    void scheduleVisit(UUID orderId, int visitNo, Instant startsAt) {
        updateVisit(orderId, visitNo, visit -> new StoredServiceVisit(visit.id(), visit.serviceOrderId(),
                visit.visitNo(), visit.plannedDate(), startsAt, ServiceVisitStatus.SCHEDULED,
                visit.progress(), null, null, null, visit.version() + 1));
    }

    void expireAttempts(Instant payBefore) {
        attempts.replaceAll((id, attempt) -> attempt.status() == PaymentAttemptStatus.OPEN
                ? new AttemptRef(attempt.id(), attempt.orderId(), attempt.nodeId(), attempt.tradeOrderId(),
                        attempt.amountMinor(), attempt.status(), payBefore)
                : attempt);
    }

    StoredServiceVisit visit(UUID orderId, int visitNo) {
        return orders.get(orderId).visits().get(visitNo - 1);
    }

    private StoredServiceVisit updateVisit(
            UUID orderId,
            int visitNo,
            java.util.function.UnaryOperator<StoredServiceVisit> change
    ) {
        StoredServiceOrder order = orders.get(orderId);
        List<StoredServiceVisit> visits = new ArrayList<>(order.visits());
        StoredServiceVisit updated = change.apply(visits.get(visitNo - 1));
        visits.set(visitNo - 1, updated);
        orders.put(orderId, build(orderId, order.status(), visits, order.paymentNodes(), order.version()));
        return updated;
    }

    private void refreshNodes(UUID orderId) {
        StoredServiceOrder order = orders.get(orderId);
        List<OrderPaymentNode> paymentNodes = nodes.values().stream()
                .filter(node -> node.orderId().equals(orderId))
                .map(node -> new OrderPaymentNode(node.nodeNo(), node.nodeType(), node.visitNo(), node.visitId(),
                        node.amountMinor(), node.paidAt()))
                .toList();
        orders.put(orderId, build(orderId, order.status(), order.visits(), paymentNodes, order.version()));
    }

    private static StoredServiceOrder build(
            UUID orderId,
            ServiceOrderStatus status,
            List<StoredServiceVisit> visits,
            List<OrderPaymentNode> paymentNodes,
            int version
    ) {
        return new StoredServiceOrder(
                orderId,
                "FW1",
                UUID.randomUUID(),
                UUID.randomUUID(),
                InspectionPaymentServiceTest.CUSTOMER,
                InspectionPaymentServiceTest.PROVIDER,
                PaymentMode.NODES,
                status,
                new ServiceSnapshot(UUID.randomUUID(), "物流园区季度安防巡检", null, "370500", "东营港物流园",
                        null, null, null, visits.get(0).plannedDate(), List.of()),
                List.of(),
                List.of(),
                List.copyOf(visits),
                List.copyOf(paymentNodes),
                InspectionPaymentServiceTest.NOW,
                null,
                null,
                version);
    }

    // ---- PlanStore ----

    @Override
    public Optional<PlanReservation> findReservation(UUID reservationId) {
        return Optional.empty();
    }

    @Override
    public Optional<StoredServicePlan> findPlan(UUID planId) {
        return Optional.empty();
    }

    @Override
    public Optional<StoredServiceOrder> findOrder(UUID orderId) {
        return Optional.ofNullable(orders.get(orderId));
    }

    @Override
    public ServiceOrderExtras findOrderExtras(UUID orderId) {
        return extras(orderId);
    }

    @Override
    public Optional<PlanIdempotencyEntry> findIdempotency(UUID actorId, String operation, UUID key) {
        return Optional.empty();
    }

    @Override
    public int nextPlanVersion(UUID reservationId) {
        return 1;
    }

    @Override
    public PlanWriteResult publish(PlanReservation expectedReservation, StoredServicePlan plan,
                                   PlanIdempotencyEntry entry, CurrentSubject actor, String requestId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PlanWriteResult confirm(StoredServicePlan expectedPlan, StoredServiceOrder order,
                                   PlanIdempotencyEntry entry, String agreementVersion, CurrentSubject actor,
                                   String requestId) {
        throw new UnsupportedOperationException();
    }

    // ---- ServiceOrderChangeStore ----

    @Override
    public ServiceOrderExtras extras(UUID orderId) {
        List<PaymentAttemptRecord> records = attempts.values().stream()
                .filter(attempt -> attempt.orderId().equals(orderId))
                .map(attempt -> {
                    NodeRef node = nodes.get(attempt.nodeId());
                    return new PaymentAttemptRecord(attempt.id(), node.nodeNo(), node.nodeType(), node.visitNo(),
                            attempt.tradeOrderId(), attempt.amountMinor(), attempt.status(), attempt.payBefore(),
                            attemptCreated.get(attempt.id()), null, null);
                })
                .toList();
        List<NodeRefundRecord> refunds = nodes.values().stream()
                .filter(node -> node.orderId().equals(orderId) && node.refundId() != null)
                .map(node -> new NodeRefundRecord(node.nodeNo(), node.refundId(), node.amountMinor(), "REQUESTED",
                        InspectionPaymentServiceTest.NOW))
                .toList();
        StoredServiceOrder order = orders.get(orderId);
        List<UUID> visitIds = order == null ? List.of() : order.visits().stream().map(StoredServiceVisit::id).toList();
        return new ServiceOrderExtras(
                records,
                refunds,
                reschedules.values().stream().filter(item -> visitIds.contains(item.visitId())).toList(),
                reworks.values().stream().filter(item -> visitIds.contains(item.visitId())).toList(),
                Map.copyOf(originals),
                cancellations.get(orderId));
    }

    @Override
    public Optional<NodeRef> findNode(UUID orderId, int nodeNo) {
        return nodes.values().stream()
                .filter(node -> node.orderId().equals(orderId) && node.nodeNo() == nodeNo)
                .findFirst();
    }

    @Override
    public Optional<NodeRef> findNodeById(UUID nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    @Override
    public Optional<AttemptRef> findAttempt(UUID attemptId) {
        return Optional.ofNullable(attempts.get(attemptId));
    }

    @Override
    public Optional<AttemptRef> findLiveAttempt(UUID nodeId) {
        return attempts.values().stream()
                .filter(attempt -> attempt.nodeId().equals(nodeId) && attempt.status() != PaymentAttemptStatus.CLOSED)
                .findFirst();
    }

    @Override
    public void insertAttempt(AttemptRef attempt, UUID actorId, Instant at) {
        if (findLiveAttempt(attempt.nodeId()).isPresent()) {
            throw new IllegalStateException("uq_inspection_payment_attempt_live");
        }
        attempts.put(attempt.id(), attempt);
        attemptCreated.put(attempt.id(), at);
    }

    @Override
    public boolean markAttemptPaid(UUID attemptId, Instant at) {
        AttemptRef attempt = attempts.get(attemptId);
        if (attempt == null || attempt.status() == PaymentAttemptStatus.PAID) {
            return false;
        }
        if (findLiveAttempt(attempt.nodeId()).filter(other -> !other.id().equals(attemptId)).isPresent()) {
            throw new IllegalStateException("uq_inspection_payment_attempt_live");
        }
        attempts.put(attemptId, with(attempt, PaymentAttemptStatus.PAID));
        return true;
    }

    @Override
    public boolean markAttemptClosed(UUID attemptId, Instant at) {
        AttemptRef attempt = attempts.get(attemptId);
        if (attempt == null || attempt.status() != PaymentAttemptStatus.OPEN) {
            return false;
        }
        attempts.put(attemptId, with(attempt, PaymentAttemptStatus.CLOSED));
        return true;
    }

    @Override
    public boolean markNodePaid(UUID nodeId, UUID tradeOrderId, Instant at) {
        NodeRef node = nodes.get(nodeId);
        if (node.paidAt() != null) {
            return false;
        }
        nodes.put(nodeId, new NodeRef(node.id(), node.orderId(), node.nodeNo(), node.nodeType(), node.visitNo(),
                node.visitId(), node.amountMinor(), at, tradeOrderId, null));
        refreshNodes(node.orderId());
        return true;
    }

    @Override
    public boolean markNodeRefunded(UUID nodeId, UUID refundId, Instant at) {
        NodeRef node = nodes.get(nodeId);
        if (node.refundId() != null || node.paidAt() == null) {
            return false;
        }
        nodes.put(nodeId, new NodeRef(node.id(), node.orderId(), node.nodeNo(), node.nodeType(), node.visitNo(),
                node.visitId(), node.amountMinor(), node.paidAt(), node.paidTradeOrderId(), refundId));
        return true;
    }

    @Override
    public boolean moveOrder(UUID orderId, ServiceOrderStatus expected, ServiceOrderStatus next, String actorType,
                             UUID actorId, String requestId, Instant at) {
        StoredServiceOrder order = orders.get(orderId);
        if (order.status() != expected) {
            return false;
        }
        orders.put(orderId, build(orderId, next, order.visits(), order.paymentNodes(), order.version() + 1));
        orderMoves.add(expected + ">" + next);
        return true;
    }

    @Override
    public boolean cancelOrder(UUID orderId, ServiceOrderStatus expected, int expectedVersion, String reason,
                               UUID actorId, String requestId, Instant at) {
        StoredServiceOrder order = orders.get(orderId);
        if (order.status() != expected || order.version() != expectedVersion) {
            return false;
        }
        orders.put(orderId, build(orderId, ServiceOrderStatus.CANCELLED, order.visits(), order.paymentNodes(),
                order.version() + 1));
        cancellations.put(orderId, new OrderCancellation(at, reason));
        return true;
    }

    @Override
    public void recordTransition(String aggregateType, UUID aggregateId, String from, String to, String actorType,
                                 UUID actorId, String requestId, Instant at) {
        transitions.add(aggregateType + ":" + from + ">" + to);
    }

    @Override
    public Optional<VisitRescheduleRecord> findReschedule(UUID requestId) {
        return Optional.ofNullable(reschedules.get(requestId));
    }

    @Override
    public boolean insertReschedule(VisitRescheduleRecord request, UUID orderId, UUID actorId) {
        boolean pending = reschedules.values().stream().anyMatch(item ->
                item.visitId().equals(request.visitId()) && item.status() == RescheduleStatus.REQUESTED);
        if (pending) {
            return false;
        }
        reschedules.put(request.id(), request);
        return true;
    }

    @Override
    public boolean decideReschedule(UUID requestId, RescheduleStatus status, String note, UUID actorId, Instant at) {
        VisitRescheduleRecord request = reschedules.get(requestId);
        if (request.status() != RescheduleStatus.REQUESTED) {
            return false;
        }
        reschedules.put(requestId, new VisitRescheduleRecord(request.id(), request.visitId(), request.fromDate(),
                request.requestedDate(), request.reason(), status, note, request.requestedAt(), at));
        return true;
    }

    @Override
    public boolean rescheduleVisit(UUID visitId, int expectedVersion, LocalDate plannedDate, UUID actorId,
                                   Instant at) {
        for (StoredServiceOrder order : orders.values()) {
            Optional<StoredServiceVisit> found = order.visit(visitId);
            if (found.isPresent()) {
                StoredServiceVisit visit = found.get();
                if (visit.version() != expectedVersion) {
                    return false;
                }
                originals.putIfAbsent(visitId, visit.plannedDate());
                updateVisit(order.id(), visit.visitNo(), current -> new StoredServiceVisit(current.id(),
                        current.serviceOrderId(), current.visitNo(), plannedDate, null,
                        ServiceVisitStatus.PENDING_SCHEDULE, current.progress(), null, null, null,
                        current.version() + 1));
                return true;
            }
        }
        return false;
    }

    @Override
    public int deliveryRevision(UUID deliveryId) {
        return 1;
    }

    @Override
    public boolean returnForRework(StoredServiceOrder order, ServiceOrderStatus nextOrderStatus,
                                   StoredServiceVisit visit, StoredDelivery delivery, VisitReworkRecord rework,
                                   UUID actorId, String requestId) {
        updateVisit(order.id(), visit.visitNo(), current -> new StoredServiceVisit(current.id(),
                current.serviceOrderId(), current.visitNo(), current.plannedDate(), current.plannedStartAt(),
                ServiceVisitStatus.REWORK, current.progress(), current.startedAt(), null, null,
                current.version() + 1));
        if (order.status() != nextOrderStatus) {
            moveOrder(order.id(), order.status(), nextOrderStatus, "CUSTOMER", actorId, requestId,
                    rework.requestedAt());
        }
        deliveries.put(visit.id(), new StoredDelivery(delivery.id(), delivery.serviceOrderId(), delivery.visitId(),
                DeliveryStatus.RETURNED, null, null, List.of(), 0, IssueGroupBy.SEVERITY, List.of(), List.of(),
                delivery.publishedAt(), null, delivery.version() + 1));
        reworks.put(rework.id(), rework);
        return true;
    }

    private static AttemptRef with(AttemptRef attempt, PaymentAttemptStatus status) {
        return new AttemptRef(attempt.id(), attempt.orderId(), attempt.nodeId(), attempt.tradeOrderId(),
                attempt.amountMinor(), status, attempt.payBefore());
    }

    // ---- DeliveryStore（只用到按巡检次读交付） ----

    @Override
    public Optional<StoredDelivery> findDelivery(UUID deliveryId) {
        return deliveries.values().stream().filter(item -> item.id().equals(deliveryId)).findFirst();
    }

    @Override
    public Optional<StoredDelivery> findDeliveryByVisit(UUID visitId) {
        return Optional.ofNullable(deliveries.get(visitId));
    }

    @Override
    public Optional<DeliveryFileObject> findFileObject(UUID fileId) {
        return Optional.empty();
    }

    @Override
    public Optional<StoredAcceptance> findAcceptance(UUID acceptanceId) {
        return Optional.empty();
    }

    @Override
    public Optional<StoredAcceptance> findAcceptanceByVisit(UUID visitId) {
        return Optional.empty();
    }

    @Override
    public Optional<DeliveryIdempotencyEntry> findDeliveryIdempotency(UUID actorId, String operation, UUID key) {
        return Optional.empty();
    }

    @Override
    public DeliveryWriteResult publishDelivery(StoredServiceOrder expectedOrder, ServiceOrderStatus nextOrderStatus,
                                               StoredServiceVisit expectedVisit, StoredServiceVisit deliveredVisit,
                                               StoredDelivery delivery, DeliveryIdempotencyEntry entry,
                                               CurrentSubject actor, String requestId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public DeliveryWriteResult acceptDelivery(StoredServiceOrder expectedOrder, ServiceOrderStatus nextOrderStatus,
                                              StoredServiceVisit expectedVisit, StoredServiceVisit acceptedVisit,
                                              StoredDelivery expectedDelivery, StoredDelivery acceptedDelivery,
                                              StoredAcceptance acceptance, DeliveryIdempotencyEntry entry,
                                              CurrentSubject actor, String requestId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean claimDeliveryIdempotency(DeliveryIdempotencyEntry entry, String resourceType,
                                            int responseStatus) {
        return true;
    }
}

/** 交易模块的替身：记下建了哪些订单、关了哪些、退了哪些。 */
final class FakeTrade implements InspectionPaymentPort {

    record Refund(UUID tradeOrderId, long amountMinor, String reason, UUID key) {
    }

    final List<OpenPayment> opened = new ArrayList<>();
    final Map<UUID, UUID> attemptByTradeOrder = new HashMap<>();
    final List<UUID> cancelled = new ArrayList<>();
    final List<UUID> locked = new ArrayList<>();
    final List<Refund> refunds = new ArrayList<>();
    boolean canCollect = true;
    boolean closeOnCancel = true;
    BiConsumer<UUID, UUID> onClosed = (tradeOrderId, attemptId) -> { };

    @Override
    public boolean merchantCanCollect(UUID organizationId) {
        return canCollect;
    }

    @Override
    public OpenedPayment open(OpenPayment request) {
        if (!canCollect) {
            throw new ProviderPaymentUnavailableException();
        }
        opened.add(request);
        UUID tradeOrderId = UUID.randomUUID();
        attemptByTradeOrder.put(tradeOrderId, request.attemptId());
        return new OpenedPayment(tradeOrderId, request.payBefore());
    }

    @Override
    public void lockOrder(UUID tradeOrderId) {
        locked.add(tradeOrderId);
    }

    @Override
    public UUID requestRefund(UUID tradeOrderId, long amountMinor, String reason, UUID idempotencyKey,
                              UUID actorId) {
        refunds.add(new Refund(tradeOrderId, amountMinor, reason, idempotencyKey));
        return UUID.randomUUID();
    }

    @Override
    public Map<UUID, RefundProgress> refundStates(Collection<UUID> refundIds) {
        return Map.of();
    }

    @Override
    public boolean cancelUnpaid(UUID accountId, UUID tradeOrderId) {
        cancelled.add(tradeOrderId);
        if (closeOnCancel) {
            onClosed.accept(tradeOrderId, attemptByTradeOrder.get(tradeOrderId));
        }
        return false;
    }
}
