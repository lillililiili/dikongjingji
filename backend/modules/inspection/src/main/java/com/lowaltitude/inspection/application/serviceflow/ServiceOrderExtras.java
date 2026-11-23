package com.lowaltitude.inspection.application.serviceflow;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 服务单上和付款、取消、改期、整改有关的记录（S4-1，docs/redesign/08-后续开发计划.md §5 D9、C3、B2）。
 * 这些记录不改变既有的服务单、巡检次模型，读服务单时一并带出。
 */
record ServiceOrderExtras(
        List<PaymentAttemptRecord> attempts,
        List<NodeRefundRecord> refunds,
        List<VisitRescheduleRecord> reschedules,
        List<VisitReworkRecord> reworks,
        Map<UUID, LocalDate> originalPlannedDates,
        OrderCancellation cancellation
) {
    static final ServiceOrderExtras EMPTY =
            new ServiceOrderExtras(List.of(), List.of(), List.of(), List.of(), Map.of(), null);

    /** 这次巡检最近一次改期申请（任何状态）。 */
    Optional<VisitRescheduleRecord> latestReschedule(UUID visitId) {
        return reschedules.stream()
                .filter(item -> item.visitId().equals(visitId))
                .reduce((first, second) -> second);
    }

    /** 这次巡检最近一次退回整改。 */
    Optional<VisitReworkRecord> latestRework(UUID visitId) {
        return reworks.stream()
                .filter(item -> item.visitId().equals(visitId))
                .reduce((first, second) -> second);
    }
}

enum PaymentAttemptStatus {
    OPEN,
    PAID,
    CLOSED
}

/** 一次付款尝试：一张交易订单。状态只由交易模块的付款、关闭回调推进。 */
record PaymentAttemptRecord(
        UUID id,
        int nodeNo,
        PaymentNodeType nodeType,
        Integer visitNo,
        UUID tradeOrderId,
        long amountMinor,
        PaymentAttemptStatus status,
        Instant payBefore,
        Instant createdAt,
        Instant paidAt,
        Instant closedAt
) {
}

/** 首付的退款。status 是交易模块的退款状态（REQUESTED、PROCESSING、SUCCEEDED、CLOSED、ABNORMAL）；读不到时为 null。 */
record NodeRefundRecord(int nodeNo, UUID refundId, long amountMinor, String status, Instant requestedAt) {
}

enum RescheduleStatus {
    REQUESTED,
    APPROVED,
    REJECTED
}

record VisitRescheduleRecord(
        UUID id,
        UUID visitId,
        LocalDate fromDate,
        LocalDate requestedDate,
        String reason,
        RescheduleStatus status,
        String decisionNote,
        Instant requestedAt,
        Instant decidedAt
) {
}

record VisitReworkRecord(
        UUID id,
        UUID visitId,
        int deliveryRevision,
        String reason,
        Instant requestedAt,
        Instant resolvedAt
) {
}

record OrderCancellation(Instant cancelledAt, String reason) {
}

/**
 * 付款、取消和改期的规则，服务端和页面都按这里说的算。
 *
 * <ul>
 *   <li>D9 付款节点：确认方案后首付 30%（四舍五入到分），其余按次结算，最后一次补差，总额等于方案总价
 *       （见 {@link PaymentNodePlanner}）。金额为 0 的节点确认时直接记为已付。首付在服务单待付款时可付；
 *       按次服务费在这一次验收之后可付，不影响后续巡检；全部付清后服务单才算完成。服务单取消后不再收款。</li>
 *   <li>C3 取消：第 1 次巡检开始前（服务单待付款或待安排）客户可以在线取消；没付首付的不收费，付了的首付整笔原路退回。
 *       开始之后不能在线取消，走客服协商。</li>
 *   <li>C3 改期：每次巡检开始前 24 小时之前可以申请，开始时间按排期时间算，没排期按计划日期的北京时间零点算；
 *       新日期要晚于今天、晚于上一次、早于下一次的计划日期；服务商确认后生效（排期清空，重新排期）。</li>
 * </ul>
 */
final class PaymentRules {

    static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    static final Duration RESCHEDULE_NOTICE = Duration.ofHours(24);
    static final Duration PAYMENT_WINDOW = Duration.ofMinutes(15);

    private PaymentRules() {
    }

    /** 这个节点现在该付了（不看是否已付）。 */
    static boolean due(OrderPaymentNode node, StoredServiceOrder order) {
        if (order.status() == ServiceOrderStatus.CANCELLED || order.paymentMode() != PaymentMode.NODES) {
            return false;
        }
        if (node.nodeType() == PaymentNodeType.DOWN_PAYMENT) {
            return true;
        }
        return order.visits().stream().anyMatch(visit ->
                visit.id().equals(node.visitId()) && visit.status() == ServiceVisitStatus.ACCEPTED);
    }

    /** 第 1 次巡检开始前可以在线取消。 */
    static boolean customerCancellable(StoredServiceOrder order) {
        return (order.status() == ServiceOrderStatus.AWAITING_PAYMENT
                || order.status() == ServiceOrderStatus.ARRANGING)
                && order.visits().stream().allMatch(visit -> visit.startedAt() == null
                        && (visit.status() == ServiceVisitStatus.PENDING_SCHEDULE
                                || visit.status() == ServiceVisitStatus.SCHEDULED));
    }

    /** 这次巡检开始的时间：排了期按排期，没排按计划日期的北京时间零点。 */
    static Instant visitStartsAt(StoredServiceVisit visit) {
        return visit.plannedStartAt() != null
                ? visit.plannedStartAt()
                : visit.plannedDate().atStartOfDay(BEIJING).toInstant();
    }

    /** 现在能为这次巡检申请改期（不看有没有待处理的申请）。 */
    static boolean reschedulable(StoredServiceOrder order, StoredServiceVisit visit, Instant now) {
        boolean orderActive = order.status() == ServiceOrderStatus.ARRANGING
                || order.status() == ServiceOrderStatus.IN_PROGRESS
                || order.status() == ServiceOrderStatus.DELIVERED;
        boolean notStarted = visit.status() == ServiceVisitStatus.PENDING_SCHEDULE
                || visit.status() == ServiceVisitStatus.SCHEDULED;
        return orderActive && notStarted && now.plus(RESCHEDULE_NOTICE).isBefore(visitStartsAt(visit));
    }

    /** 所有金额大于 0 的节点都已付。 */
    static boolean settled(StoredServiceOrder order) {
        return order.paymentNodes().stream().allMatch(node -> node.paidAt() != null);
    }
}
