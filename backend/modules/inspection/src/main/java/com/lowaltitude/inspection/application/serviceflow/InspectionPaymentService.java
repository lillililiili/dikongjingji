package com.lowaltitude.inspection.application.serviceflow;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 巡检付款节点与确认方案后的取消（S4-1，docs/redesign/08-后续开发计划.md §5 D9、C3）。
 *
 * <p>钱经统一交易模块（{@link InspectionPaymentPort}）直接进服务商自己的子商户号；节点是否已付只认交易模块
 * 验签后的付款回调（{@link #onPaymentConfirmed}）。每个节点每发起一次付款建一张交易订单（一次“付款尝试”），
 * 超时或取消只关这一次，节点仍待付，可以再发起；同一节点同时只有一张待付或已付的订单。
 *
 * <p>加锁顺序和交易模块约定的一样：先交易订单，再服务单。
 */
public class InspectionPaymentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(InspectionPaymentService.class);
    static final String CANCEL_REFUND_REASON = "巡检服务单取消，首付原路退回";
    private static final int MAX_CANCEL_REASON = 200;
    private static final int MAX_ORDER_TITLE = 160;

    private final PlanStore orders;
    private final ServiceOrderChangeStore changes;
    private final Supplier<InspectionPaymentPort> payments;
    private final InTransaction tx;
    private final Clock clock;

    public InspectionPaymentService(
            PlanStore orders,
            ServiceOrderChangeStore changes,
            Supplier<InspectionPaymentPort> payments,
            InTransaction tx,
            Clock clock
    ) {
        this.orders = orders;
        this.changes = changes;
        this.payments = payments;
        this.tx = tx;
        this.clock = clock;
    }

    // ---- 付款 ----

    /**
     * 为一个付款节点发起付款，返回要去付的那张交易订单（App 带着它去收银台）。
     * 已有待付的订单就返回它；它已经过了付款截止时间时先关掉再建新的；节点已付时返回已付的那张（App 去支付结果）。
     * 节点还没到（首付以外的要等这一次验收）、服务单已取消时拒绝。
     */
    public PaymentLaunchView payNode(CurrentSubject subject, UUID orderId, int nodeNo, String requestId) {
        requireCustomer(subject);
        if (orderId == null || nodeNo < 1) {
            throw new PlanValidationException();
        }
        StoredServiceOrder order = ownOrder(subject, orderId);
        NodeRef node = changes.findNode(order.id(), nodeNo).orElseThrow(PlanResourceNotFoundException::new);
        Optional<AttemptRef> live = changes.findLiveAttempt(node.id());
        Instant now = clock.instant();
        if (live.isPresent()
                && live.get().status() == PaymentAttemptStatus.OPEN
                && !live.get().payBefore().isAfter(now)) {
            // 过了付款截止时间的那张先在交易模块关掉（发起过支付的先查单）；查单发现已付时下面按已付返回。
            payments.get().cancelUnpaid(subject.subjectId(), live.get().tradeOrderId());
        }
        return tx.call(() -> openAttempt(subject, orderId, nodeNo, requestId));
    }

    private PaymentLaunchView openAttempt(CurrentSubject subject, UUID orderId, int nodeNo, String requestId) {
        orders.lockOrder(orderId);
        StoredServiceOrder order = ownOrder(subject, orderId);
        NodeRef node = changes.findNode(orderId, nodeNo).orElseThrow(PlanResourceNotFoundException::new);
        if (node.paidAt() != null) {
            return new PaymentLaunchView(nodeNo, node.paidTradeOrderId(), null, true);
        }
        OrderPaymentNode orderNode = orderNode(order, nodeNo);
        if (!PaymentRules.due(orderNode, order)) {
            throw new PaymentNotDueException();
        }
        Instant now = clock.instant();
        Optional<AttemptRef> live = changes.findLiveAttempt(node.id());
        if (live.isPresent()) {
            AttemptRef attempt = live.get();
            return new PaymentLaunchView(nodeNo, attempt.tradeOrderId(), attempt.payBefore(),
                    attempt.status() == PaymentAttemptStatus.PAID);
        }
        UUID attemptId = UUID.randomUUID();
        InspectionPaymentPort.OpenedPayment opened = payments.get().open(new InspectionPaymentPort.OpenPayment(
                attemptId,
                order.customerId(),
                order.providerId(),
                order.serviceSnapshot().cityCode(),
                order.id(),
                orderTitle(order),
                nodeLabel(orderNode, order.visits().size()),
                order.serviceSnapshot().serviceCoverUrl(),
                node.amountMinor(),
                now.plus(PaymentRules.PAYMENT_WINDOW)));
        changes.insertAttempt(
                new AttemptRef(attemptId, orderId, node.id(), opened.tradeOrderId(), node.amountMinor(),
                        PaymentAttemptStatus.OPEN, opened.payBefore()),
                subject.subjectId(),
                now);
        changes.recordTransition("PAYMENT_ATTEMPT", attemptId, null, PaymentAttemptStatus.OPEN.name(),
                "CUSTOMER", subject.subjectId(), requestId(requestId), now);
        return new PaymentLaunchView(nodeNo, opened.tradeOrderId(), opened.payBefore(), false);
    }

    /**
     * 交易订单付款到账（在交易模块改订单状态的同一个事务里，交易订单已锁）。节点记为已付；首付到账时服务单从待付款进入待安排。
     * 返回 false 时交易模块整笔原路退款：服务单已取消、节点已经由另一张订单付过，或关闭后才到账而节点又发起了新的付款。
     */
    public boolean onPaymentConfirmed(UUID tradeOrderId, UUID attemptId) {
        Optional<AttemptRef> found = changes.findAttempt(attemptId);
        if (found.isEmpty() || !found.get().tradeOrderId().equals(tradeOrderId)) {
            LOGGER.error("trade order {} was paid for an unknown inspection payment {}", tradeOrderId, attemptId);
            return false;
        }
        orders.lockOrder(found.get().orderId());
        AttemptRef attempt = changes.findAttempt(attemptId).orElseThrow();
        if (attempt.status() == PaymentAttemptStatus.PAID) {
            return true;
        }
        StoredServiceOrder order = orders.findOrder(attempt.orderId()).orElseThrow();
        NodeRef node = changes.findNodeById(attempt.nodeId()).orElseThrow();
        if (order.status() == ServiceOrderStatus.CANCELLED || node.paidAt() != null) {
            LOGGER.warn("inspection payment {} arrived after its order was cancelled or its node was paid;"
                    + " it will be refunded", attemptId);
            return false;
        }
        if (attempt.status() == PaymentAttemptStatus.CLOSED && changes.findLiveAttempt(node.id()).isPresent()) {
            LOGGER.warn("inspection payment {} arrived after it closed and a new payment was opened;"
                    + " it will be refunded", attemptId);
            return false;
        }
        Instant now = clock.instant();
        if (!changes.markAttemptPaid(attemptId, now) || !changes.markNodePaid(node.id(), tradeOrderId, now)) {
            throw new IllegalStateException("inspection payment changed while its order was locked");
        }
        changes.recordTransition("PAYMENT_ATTEMPT", attemptId, attempt.status().name(),
                PaymentAttemptStatus.PAID.name(), "SYSTEM", null, "trade:" + tradeOrderId, now);
        if (node.nodeType() == PaymentNodeType.DOWN_PAYMENT && order.status() == ServiceOrderStatus.AWAITING_PAYMENT) {
            changes.moveOrder(order.id(), ServiceOrderStatus.AWAITING_PAYMENT, ServiceOrderStatus.ARRANGING,
                    "SYSTEM", null, "trade:" + tradeOrderId, now);
        }
        return true;
    }

    /** 待付款的交易订单关闭了（买家取消或超时）：这次尝试关闭，节点仍待付。 */
    public void onPaymentClosed(UUID tradeOrderId, UUID attemptId) {
        Optional<AttemptRef> attempt = changes.findAttempt(attemptId);
        if (attempt.isEmpty() || !attempt.get().tradeOrderId().equals(tradeOrderId)) {
            LOGGER.error("trade order {} closed for an unknown inspection payment {}", tradeOrderId, attemptId);
            return;
        }
        Instant now = clock.instant();
        if (changes.markAttemptClosed(attemptId, now)) {
            changes.recordTransition("PAYMENT_ATTEMPT", attemptId, PaymentAttemptStatus.OPEN.name(),
                    PaymentAttemptStatus.CLOSED.name(), "SYSTEM", null, "trade:" + tradeOrderId, now);
        }
    }

    // ---- 取消 ----

    /**
     * C3：第 1 次巡检开始前客户在线取消。首付没付的不收费（进行中的付款先关掉）；付了的首付整笔原路退回（退款提交后交给渠道）。
     * 已取消的再取消一次原样返回；巡检开始后拒绝（走客服协商）。
     */
    public ServiceOrderView cancel(
            CurrentSubject subject,
            UUID orderId,
            int expectedVersion,
            String reason,
            String requestId
    ) {
        requireCustomer(subject);
        String normalizedReason = reason == null || reason.isBlank() ? null : reason.trim();
        if (orderId == null || expectedVersion < 0
                || normalizedReason != null && normalizedReason.length() > MAX_CANCEL_REASON) {
            throw new PlanValidationException();
        }
        StoredServiceOrder order = ownOrder(subject, orderId);
        if (order.status() == ServiceOrderStatus.CANCELLED) {
            return ServiceOrderView.from(order, orders);
        }
        if (order.version() != expectedVersion) {
            throw new PlanVersionConflictException();
        }
        if (!PaymentRules.customerCancellable(order)) {
            throw new OrderNotCancellableException();
        }
        // 进行中的首付先在交易模块关掉（不能在事务里调）；查单发现刚付了的，下面按已付退款。
        for (OrderPaymentNode node : order.paymentNodes()) {
            changes.findNode(orderId, node.nodeNo())
                    .flatMap(ref -> changes.findLiveAttempt(ref.id()))
                    .filter(attempt -> attempt.status() == PaymentAttemptStatus.OPEN)
                    .ifPresent(attempt -> payments.get().cancelUnpaid(subject.subjectId(), attempt.tradeOrderId()));
        }
        return tx.call(() -> cancelLocked(subject, orderId, normalizedReason, requestId(requestId)));
    }

    private ServiceOrderView cancelLocked(CurrentSubject subject, UUID orderId, String reason, String requestId) {
        StoredServiceOrder before = ownOrder(subject, orderId);
        Optional<NodeRef> paidDown = before.paymentNodes().stream()
                .filter(node -> node.nodeType() == PaymentNodeType.DOWN_PAYMENT)
                .findFirst()
                .flatMap(node -> changes.findNode(orderId, node.nodeNo()))
                .filter(node -> node.paidAt() != null && node.paidTradeOrderId() != null);
        // 先锁首付的交易订单，再锁服务单。
        paidDown.ifPresent(node -> payments.get().lockOrder(node.paidTradeOrderId()));
        orders.lockOrder(orderId);
        StoredServiceOrder order = ownOrder(subject, orderId);
        if (order.status() == ServiceOrderStatus.CANCELLED) {
            return ServiceOrderView.from(order, orders);
        }
        if (!PaymentRules.customerCancellable(order)) {
            throw new OrderNotCancellableException();
        }
        Instant now = clock.instant();
        Optional<NodeRef> refundable = paidDown.flatMap(node -> changes.findNodeById(node.id()))
                .filter(node -> node.paidAt() != null && node.refundId() == null && node.amountMinor() > 0);
        if (refundable.isPresent()) {
            NodeRef node = refundable.get();
            UUID refundId = payments.get().requestRefund(
                    node.paidTradeOrderId(),
                    node.amountMinor(),
                    CANCEL_REFUND_REASON,
                    refundKey(orderId),
                    subject.subjectId());
            if (!changes.markNodeRefunded(node.id(), refundId, now)) {
                throw new IllegalStateException("down payment changed while its order was locked");
            }
        }
        if (!changes.cancelOrder(orderId, order.status(), order.version(), reason, subject.subjectId(), requestId,
                now)) {
            throw new PlanVersionConflictException();
        }
        return ServiceOrderView.from(ownOrder(subject, orderId), orders);
    }

    // ---- 工具 ----

    /** 同一张服务单的取消退款只有一笔：幂等键由服务单 ID 推出。 */
    static UUID refundKey(UUID orderId) {
        return UUID.nameUUIDFromBytes(("inspection-cancel-refund:" + orderId).getBytes(StandardCharsets.UTF_8));
    }

    /** 订单上的节点名：“首付 30%”；只有一次巡检时“验收后付款 70%”；多次时“第 2/6 次服务费”。 */
    static String nodeLabel(OrderPaymentNode node, int visitCount) {
        if (node.nodeType() == PaymentNodeType.DOWN_PAYMENT) {
            return "首付 30%";
        }
        return visitCount == 1 ? "验收后付款 70%" : "第 " + node.visitNo() + "/" + visitCount + " 次服务费";
    }

    private String orderTitle(StoredServiceOrder order) {
        String version = orders.findPlan(order.planId())
                .map(plan -> " · 方案 v" + plan.planVersion())
                .orElse("");
        String title = order.serviceSnapshot().serviceTitle().trim();
        int room = MAX_ORDER_TITLE - version.length();
        return (title.length() > room ? title.substring(0, room) : title) + version;
    }

    private static OrderPaymentNode orderNode(StoredServiceOrder order, int nodeNo) {
        return order.paymentNodes().stream()
                .filter(node -> node.nodeNo() == nodeNo)
                .findFirst()
                .orElseThrow(PlanResourceNotFoundException::new);
    }

    private StoredServiceOrder ownOrder(CurrentSubject subject, UUID orderId) {
        StoredServiceOrder order = orders.findOrder(orderId).orElseThrow(PlanResourceNotFoundException::new);
        if (!order.customerId().equals(subject.subjectId())) {
            throw new PlanResourceNotFoundException();
        }
        return order;
    }

    private static void requireCustomer(CurrentSubject subject) {
        if (subject == null
                || !subject.roles().contains("CUSTOMER")
                || !subject.permissions().contains("inspection.plan.confirm.own")) {
            throw new PlanForbiddenException();
        }
    }

    private static String requestId(String value) {
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value.trim();
    }
}

/**
 * 发起付款的结果：要去付的交易订单（paid 为 true 时这张已经付过，App 直接去支付结果）。
 * payBefore 是付款截止时间，已付时为 null。
 */
record PaymentLaunchView(int nodeNo, UUID tradeOrderId, Instant payBefore, boolean paid) {
}
