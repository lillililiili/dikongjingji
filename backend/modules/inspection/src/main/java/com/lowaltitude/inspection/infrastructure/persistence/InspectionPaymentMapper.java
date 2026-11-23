package com.lowaltitude.inspection.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 付款节点、付款尝试、取消、改期和退回整改（S4-1，迁移 V013）。 */
public interface InspectionPaymentMapper {

    // ---- 付款节点与付款尝试 ----

    @Select("""
            SELECT n.id, n.service_order_id, n.node_no, n.node_type, v.visit_no, n.visit_id,
                   n.amount_minor, n.paid_at, n.trade_payment_id, n.refund_id
            FROM inspection_order_payment_node n
            LEFT JOIN inspection_service_visit v ON v.id = n.visit_id
            WHERE n.service_order_id = #{orderId}
              AND n.node_no = #{nodeNo}
            """)
    Optional<PaymentNodeRow> findNode(@Param("orderId") UUID orderId, @Param("nodeNo") int nodeNo);

    @Select("""
            SELECT n.id, n.service_order_id, n.node_no, n.node_type, v.visit_no, n.visit_id,
                   n.amount_minor, n.paid_at, n.trade_payment_id, n.refund_id
            FROM inspection_order_payment_node n
            LEFT JOIN inspection_service_visit v ON v.id = n.visit_id
            WHERE n.id = #{nodeId}
            """)
    Optional<PaymentNodeRow> findNodeById(@Param("nodeId") UUID nodeId);

    @Select("""
            SELECT a.id, a.service_order_id, a.node_id, n.node_no, n.node_type, v.visit_no,
                   a.trade_order_id, a.amount_minor, a.status, a.pay_before,
                   a.created_at, a.paid_at, a.closed_at
            FROM inspection_payment_attempt a
            JOIN inspection_order_payment_node n ON n.id = a.node_id
            LEFT JOIN inspection_service_visit v ON v.id = n.visit_id
            WHERE a.id = #{attemptId}
            """)
    Optional<PaymentAttemptRow> findAttempt(@Param("attemptId") UUID attemptId);

    @Select("""
            SELECT a.id, a.service_order_id, a.node_id, n.node_no, n.node_type, v.visit_no,
                   a.trade_order_id, a.amount_minor, a.status, a.pay_before,
                   a.created_at, a.paid_at, a.closed_at
            FROM inspection_payment_attempt a
            JOIN inspection_order_payment_node n ON n.id = a.node_id
            LEFT JOIN inspection_service_visit v ON v.id = n.visit_id
            WHERE a.node_id = #{nodeId}
              AND a.status IN ('OPEN', 'PAID')
            """)
    Optional<PaymentAttemptRow> findLiveAttempt(@Param("nodeId") UUID nodeId);

    @Select("""
            SELECT a.id, a.service_order_id, a.node_id, n.node_no, n.node_type, v.visit_no,
                   a.trade_order_id, a.amount_minor, a.status, a.pay_before,
                   a.created_at, a.paid_at, a.closed_at
            FROM inspection_payment_attempt a
            JOIN inspection_order_payment_node n ON n.id = a.node_id
            LEFT JOIN inspection_service_visit v ON v.id = n.visit_id
            WHERE a.service_order_id = #{orderId}
            ORDER BY a.created_at, a.id
            """)
    List<PaymentAttemptRow> findAttemptsByOrder(@Param("orderId") UUID orderId);

    @Insert("""
            INSERT INTO inspection_payment_attempt (
                id, service_order_id, node_id, trade_order_id, amount_minor, status,
                pay_before, created_by, created_at, updated_at
            )
            VALUES (
                #{id}, #{orderId}, #{nodeId}, #{tradeOrderId}, #{amountMinor}, 'OPEN',
                #{payBefore}, #{actorId}, #{createdAt}, #{createdAt}
            )
            """)
    void insertAttempt(
            @Param("id") UUID id,
            @Param("orderId") UUID orderId,
            @Param("nodeId") UUID nodeId,
            @Param("tradeOrderId") UUID tradeOrderId,
            @Param("amountMinor") long amountMinor,
            @Param("payBefore") Instant payBefore,
            @Param("actorId") UUID actorId,
            @Param("createdAt") Instant createdAt);

    /** 待付的尝试到账；关闭之后才到账的也从 CLOSED 改成 PAID（调用方先确认节点还能收）。 */
    @Update("""
            UPDATE inspection_payment_attempt
            SET status = 'PAID', paid_at = #{paidAt}, closed_at = NULL, updated_at = #{paidAt}
            WHERE id = #{attemptId}
              AND status IN ('OPEN', 'CLOSED')
            """)
    int markAttemptPaid(@Param("attemptId") UUID attemptId, @Param("paidAt") Instant paidAt);

    @Update("""
            UPDATE inspection_payment_attempt
            SET status = 'CLOSED', closed_at = #{closedAt}, updated_at = #{closedAt}
            WHERE id = #{attemptId}
              AND status = 'OPEN'
            """)
    int markAttemptClosed(@Param("attemptId") UUID attemptId, @Param("closedAt") Instant closedAt);

    @Update("""
            UPDATE inspection_order_payment_node
            SET paid_at = #{paidAt},
                trade_payment_id = #{tradeOrderId},
                version = version + 1,
                updated_at = #{paidAt}
            WHERE id = #{nodeId}
              AND paid_at IS NULL
            """)
    int markNodePaid(
            @Param("nodeId") UUID nodeId,
            @Param("tradeOrderId") UUID tradeOrderId,
            @Param("paidAt") Instant paidAt);

    @Update("""
            UPDATE inspection_order_payment_node
            SET refund_id = #{refundId},
                refund_requested_at = #{requestedAt},
                version = version + 1,
                updated_at = #{requestedAt}
            WHERE id = #{nodeId}
              AND refund_id IS NULL
              AND paid_at IS NOT NULL
            """)
    int markNodeRefunded(
            @Param("nodeId") UUID nodeId,
            @Param("refundId") UUID refundId,
            @Param("requestedAt") Instant requestedAt);

    @Select("""
            SELECT n.node_no, n.refund_id, n.amount_minor, n.refund_requested_at
            FROM inspection_order_payment_node n
            WHERE n.service_order_id = #{orderId}
              AND n.refund_id IS NOT NULL
            ORDER BY n.node_no
            """)
    List<NodeRefundRow> findNodeRefunds(@Param("orderId") UUID orderId);

    // ---- 服务单状态 ----

    @Select("""
            SELECT status, version, cancelled_at, cancel_reason
            FROM inspection_service_order
            WHERE id = #{orderId}
            """)
    Optional<OrderStateRow> findOrderState(@Param("orderId") UUID orderId);

    /** 交易回调推进时没有操作人（actorId 为 null），保留上一个操作人。 */
    @Update("""
            UPDATE inspection_service_order
            SET status = #{nextStatus},
                version = version + 1,
                updated_by = COALESCE(#{actorId}, updated_by),
                updated_at = #{at}
            WHERE id = #{orderId}
              AND status = #{expectedStatus}
            """)
    int updateOrderStatus(
            @Param("orderId") UUID orderId,
            @Param("expectedStatus") String expectedStatus,
            @Param("nextStatus") String nextStatus,
            @Param("actorId") UUID actorId,
            @Param("at") Instant at);

    @Update("""
            UPDATE inspection_service_order
            SET status = 'CANCELLED',
                cancelled_at = #{at},
                cancelled_by = #{actorId},
                cancel_reason = #{reason},
                version = version + 1,
                updated_by = #{actorId},
                updated_at = #{at}
            WHERE id = #{orderId}
              AND status = #{expectedStatus}
              AND version = #{expectedVersion}
            """)
    int cancelOrder(
            @Param("orderId") UUID orderId,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersion") int expectedVersion,
            @Param("reason") String reason,
            @Param("actorId") UUID actorId,
            @Param("at") Instant at);

    // ---- 改期 ----

    @Select("""
            SELECT id, service_order_id, visit_id, from_date, requested_date, reason, status,
                   decision_note, requested_at, decided_at
            FROM inspection_visit_reschedule
            WHERE service_order_id = #{orderId}
            ORDER BY requested_at, id
            """)
    List<RescheduleRow> findReschedulesByOrder(@Param("orderId") UUID orderId);

    @Select("""
            SELECT id, service_order_id, visit_id, from_date, requested_date, reason, status,
                   decision_note, requested_at, decided_at
            FROM inspection_visit_reschedule
            WHERE id = #{requestId}
            """)
    Optional<RescheduleRow> findReschedule(@Param("requestId") UUID requestId);

    @Insert("""
            INSERT INTO inspection_visit_reschedule (
                id, service_order_id, visit_id, from_date, requested_date, reason, status,
                requested_by, requested_at
            )
            VALUES (
                #{id}, #{orderId}, #{visitId}, #{fromDate}, #{requestedDate}, #{reason}, 'REQUESTED',
                #{actorId}, #{at}
            )
            ON CONFLICT DO NOTHING
            """)
    int insertReschedule(
            @Param("id") UUID id,
            @Param("orderId") UUID orderId,
            @Param("visitId") UUID visitId,
            @Param("fromDate") LocalDate fromDate,
            @Param("requestedDate") LocalDate requestedDate,
            @Param("reason") String reason,
            @Param("actorId") UUID actorId,
            @Param("at") Instant at);

    @Update("""
            UPDATE inspection_visit_reschedule
            SET status = #{status},
                decision_note = #{note},
                decided_by = #{actorId},
                decided_at = #{at}
            WHERE id = #{requestId}
              AND status = 'REQUESTED'
            """)
    int decideReschedule(
            @Param("requestId") UUID requestId,
            @Param("status") String status,
            @Param("note") String note,
            @Param("actorId") UUID actorId,
            @Param("at") Instant at);

    /** 改期生效：换成新日期、清掉排期回到待排期，第一次改期时记下原定日期。 */
    @Update("""
            UPDATE inspection_service_visit
            SET original_planned_date = COALESCE(original_planned_date, planned_date),
                planned_date = #{plannedDate},
                planned_start_at = NULL,
                status = 'PENDING_SCHEDULE',
                version = version + 1,
                updated_by = #{actorId},
                updated_at = #{at}
            WHERE id = #{visitId}
              AND status IN ('PENDING_SCHEDULE', 'SCHEDULED')
              AND version = #{expectedVersion}
            """)
    int rescheduleVisit(
            @Param("visitId") UUID visitId,
            @Param("expectedVersion") int expectedVersion,
            @Param("plannedDate") LocalDate plannedDate,
            @Param("actorId") UUID actorId,
            @Param("at") Instant at);

    @Select("""
            SELECT id visit_id, original_planned_date
            FROM inspection_service_visit
            WHERE service_order_id = #{orderId}
              AND original_planned_date IS NOT NULL
            """)
    List<OriginalDateRow> findOriginalPlannedDates(@Param("orderId") UUID orderId);

    // ---- 退回整改 ----

    @Select("""
            SELECT id, visit_id, delivery_revision, reason, requested_at, resolved_at
            FROM inspection_visit_rework
            WHERE service_order_id = #{orderId}
            ORDER BY requested_at, id
            """)
    List<ReworkRow> findReworksByOrder(@Param("orderId") UUID orderId);

    @Select("SELECT revision FROM inspection_delivery WHERE id = #{deliveryId}")
    Optional<Integer> findDeliveryRevision(@Param("deliveryId") UUID deliveryId);

    @Insert("""
            INSERT INTO inspection_visit_rework (
                id, service_order_id, visit_id, delivery_id, delivery_revision, reason,
                requested_by, requested_at
            )
            VALUES (
                #{id}, #{orderId}, #{visitId}, #{deliveryId}, #{revision}, #{reason},
                #{actorId}, #{at}
            )
            """)
    void insertRework(
            @Param("id") UUID id,
            @Param("orderId") UUID orderId,
            @Param("visitId") UUID visitId,
            @Param("deliveryId") UUID deliveryId,
            @Param("revision") int revision,
            @Param("reason") String reason,
            @Param("actorId") UUID actorId,
            @Param("at") Instant at);

    @Update("""
            UPDATE inspection_visit_rework
            SET resolved_at = #{at}
            WHERE visit_id = #{visitId}
              AND resolved_at IS NULL
            """)
    int resolveReworks(@Param("visitId") UUID visitId, @Param("at") Instant at);

    @Update("""
            UPDATE inspection_service_visit
            SET status = 'REWORK',
                delivered_at = NULL,
                version = version + 1,
                updated_by = #{actorId},
                updated_at = #{at}
            WHERE id = #{visitId}
              AND status = 'DELIVERED'
              AND version = #{expectedVersion}
            """)
    int returnVisitForRework(
            @Param("visitId") UUID visitId,
            @Param("expectedVersion") int expectedVersion,
            @Param("actorId") UUID actorId,
            @Param("at") Instant at);

    @Update("""
            UPDATE inspection_delivery
            SET status = 'RETURNED',
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{deliveryId}
              AND status = 'PUBLISHED'
              AND version = #{expectedVersion}
            """)
    int returnDelivery(
            @Param("deliveryId") UUID deliveryId,
            @Param("expectedVersion") int expectedVersion,
            @Param("at") Instant at);

    @Update("""
            UPDATE inspection_service_visit
            SET status = 'DELIVERED',
                delivered_at = #{at},
                version = version + 1,
                updated_by = #{actorId},
                updated_at = #{at}
            WHERE id = #{visitId}
              AND status = 'REWORK'
              AND version = #{expectedVersion}
            """)
    int redeliverVisit(
            @Param("visitId") UUID visitId,
            @Param("expectedVersion") int expectedVersion,
            @Param("actorId") UUID actorId,
            @Param("at") Instant at);

    @Update("""
            UPDATE inspection_delivery
            SET status = 'PUBLISHED',
                revision = revision + 1,
                tower_count = #{towerCount},
                inspected_length_meters = #{inspectedLengthMeters},
                metrics = CAST(#{metricsJson} AS jsonb),
                issue_count = #{issueCount},
                issue_group_by = #{issueGroupBy},
                issue_summary = CAST(#{issueSummaryJson} AS jsonb),
                published_by = #{actorId},
                published_at = #{publishedAt},
                version = version + 1,
                updated_at = #{publishedAt}
            WHERE id = #{deliveryId}
              AND status = 'RETURNED'
              AND version = #{expectedVersion}
            """)
    int republishDelivery(
            @Param("deliveryId") UUID deliveryId,
            @Param("expectedVersion") int expectedVersion,
            @Param("towerCount") Integer towerCount,
            @Param("inspectedLengthMeters") Long inspectedLengthMeters,
            @Param("metricsJson") String metricsJson,
            @Param("issueCount") int issueCount,
            @Param("issueGroupBy") String issueGroupBy,
            @Param("issueSummaryJson") String issueSummaryJson,
            @Param("actorId") UUID actorId,
            @Param("publishedAt") Instant publishedAt);

    @Delete("DELETE FROM inspection_delivery_file WHERE delivery_id = #{deliveryId}")
    int deleteDeliveryFiles(@Param("deliveryId") UUID deliveryId);

    // ---- 行 ----

    record PaymentNodeRow(
            UUID id,
            UUID serviceOrderId,
            int nodeNo,
            String nodeType,
            Integer visitNo,
            UUID visitId,
            long amountMinor,
            Instant paidAt,
            UUID tradePaymentId,
            UUID refundId
    ) {
    }

    record PaymentAttemptRow(
            UUID id,
            UUID serviceOrderId,
            UUID nodeId,
            int nodeNo,
            String nodeType,
            Integer visitNo,
            UUID tradeOrderId,
            long amountMinor,
            String status,
            Instant payBefore,
            Instant createdAt,
            Instant paidAt,
            Instant closedAt
    ) {
    }

    record NodeRefundRow(int nodeNo, UUID refundId, long amountMinor, Instant refundRequestedAt) {
    }

    record OrderStateRow(String status, int version, Instant cancelledAt, String cancelReason) {
    }

    record RescheduleRow(
            UUID id,
            UUID serviceOrderId,
            UUID visitId,
            LocalDate fromDate,
            LocalDate requestedDate,
            String reason,
            String status,
            String decisionNote,
            Instant requestedAt,
            Instant decidedAt
    ) {
    }

    record OriginalDateRow(UUID visitId, LocalDate originalPlannedDate) {
    }

    record ReworkRow(
            UUID id,
            UUID visitId,
            int deliveryRevision,
            String reason,
            Instant requestedAt,
            Instant resolvedAt
    ) {
    }
}
