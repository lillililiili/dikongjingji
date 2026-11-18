package com.lowaltitude.inspection.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.application.outbox.InspectionOutboxRows.EventContextRow;
import com.lowaltitude.inspection.application.outbox.InspectionOutboxRows.PendingEventRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 发件箱转发用的读写。上下文查询只取订阅方需要的稳定标识和服务名，
 * 不取联系人、地址、备注这些加密保存的内容。
 */
public interface InspectionOutboxMapper {

    String CONTEXT_COLUMNS = """
            SELECT r.customer_id,
                   r.id reservation_id,
                   r.reservation_no,
                   r.service_title_snapshot service_title,
            """;

    @Select("""
            SELECT id
            FROM inspection_domain_event_outbox
            WHERE published_at IS NULL
              AND next_attempt_at <= #{now}
            ORDER BY occurred_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<UUID> findPendingIds(@Param("now") Instant now, @Param("limit") int limit);

    @Select("""
            SELECT id,
                   aggregate_type,
                   aggregate_id,
                   event_type,
                   occurred_at,
                   attempt_count
            FROM inspection_domain_event_outbox
            WHERE id = #{eventId}
              AND published_at IS NULL
            FOR UPDATE SKIP LOCKED
            """)
    Optional<PendingEventRow> lockPending(@Param("eventId") UUID eventId);

    @Select(CONTEXT_COLUMNS + """
                   CAST(NULL AS uuid) plan_id,
                   CAST(NULL AS uuid) service_order_id,
                   CAST(NULL AS uuid) visit_id,
                   CAST(NULL AS integer) visit_no,
                   CAST(NULL AS timestamptz) planned_start_at
            FROM inspection_reservation r
            WHERE r.id = #{aggregateId}
            """)
    Optional<EventContextRow> findReservationContext(@Param("aggregateId") UUID aggregateId);

    @Select(CONTEXT_COLUMNS + """
                   p.id plan_id,
                   CAST(NULL AS uuid) service_order_id,
                   CAST(NULL AS uuid) visit_id,
                   CAST(NULL AS integer) visit_no,
                   CAST(NULL AS timestamptz) planned_start_at
            FROM inspection_service_plan p
            JOIN inspection_reservation r ON r.id = p.reservation_id
            WHERE p.id = #{aggregateId}
            """)
    Optional<EventContextRow> findPlanContext(@Param("aggregateId") UUID aggregateId);

    @Select(CONTEXT_COLUMNS + """
                   o.plan_id,
                   o.id service_order_id,
                   CAST(NULL AS uuid) visit_id,
                   CAST(NULL AS integer) visit_no,
                   CAST(NULL AS timestamptz) planned_start_at
            FROM inspection_service_order o
            JOIN inspection_reservation r ON r.id = o.reservation_id
            WHERE o.id = #{aggregateId}
            """)
    Optional<EventContextRow> findOrderContext(@Param("aggregateId") UUID aggregateId);

    @Select(CONTEXT_COLUMNS + """
                   o.plan_id,
                   o.id service_order_id,
                   v.id visit_id,
                   v.visit_no,
                   v.planned_start_at
            FROM inspection_service_visit v
            JOIN inspection_service_order o ON o.id = v.service_order_id
            JOIN inspection_reservation r ON r.id = o.reservation_id
            WHERE v.id = #{aggregateId}
            """)
    Optional<EventContextRow> findVisitContext(@Param("aggregateId") UUID aggregateId);

    @Select(CONTEXT_COLUMNS + """
                   o.plan_id,
                   o.id service_order_id,
                   v.id visit_id,
                   v.visit_no,
                   v.planned_start_at
            FROM inspection_delivery d
            JOIN inspection_service_visit v ON v.id = d.visit_id
            JOIN inspection_service_order o ON o.id = d.service_order_id
            JOIN inspection_reservation r ON r.id = o.reservation_id
            WHERE d.id = #{aggregateId}
            """)
    Optional<EventContextRow> findDeliveryContext(@Param("aggregateId") UUID aggregateId);

    @Update("""
            UPDATE inspection_domain_event_outbox
            SET published_at = #{at},
                last_error = NULL
            WHERE id = #{eventId}
              AND published_at IS NULL
            """)
    int markPublished(@Param("eventId") UUID eventId, @Param("at") Instant at);

    @Update("""
            UPDATE inspection_domain_event_outbox
            SET attempt_count = attempt_count + 1,
                next_attempt_at = #{nextAttemptAt},
                last_error = #{error}
            WHERE id = #{eventId}
              AND published_at IS NULL
            """)
    int markFailed(
            @Param("eventId") UUID eventId,
            @Param("error") String error,
            @Param("nextAttemptAt") Instant nextAttemptAt
    );
}
