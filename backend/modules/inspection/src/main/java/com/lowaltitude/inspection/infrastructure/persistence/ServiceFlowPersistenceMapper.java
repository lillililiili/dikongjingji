package com.lowaltitude.inspection.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ServiceFlowPersistenceMapper {

    @Select("""
            SELECT r.id, r.reservation_no, r.customer_id, r.provider_id, r.status,
                   r.service_id, r.service_title_snapshot service_title,
                   r.service_cover_url_snapshot service_cover_url, r.city_code,
                   r.address_ciphertext,
                   ST_X(r.location::geometry) longitude,
                   ST_Y(r.location::geometry) latitude,
                   r.line_length_meters, r.version
            FROM inspection_reservation r
            WHERE r.id = #{reservationId}
            """)
    Optional<ReservationRow> findReservation(@Param("reservationId") UUID reservationId);

    @Select("""
            SELECT item_code code, item_name_snapshot label
            FROM inspection_reservation_item
            WHERE reservation_id = #{reservationId}
            ORDER BY display_order, id
            """)
    List<CodeLabelRow> findReservationItems(@Param("reservationId") UUID reservationId);

    @Select("""
            SELECT p.id, p.plan_no, p.reservation_id, p.provider_id, p.status,
                   p.plan_version, p.valid_until, p.service_date, p.summary,
                   p.total_amount_minor, p.currency, p.published_at,
                   p.confirmed_at, o.id service_order_id, p.version
            FROM inspection_service_plan p
            LEFT JOIN inspection_service_order o ON o.plan_id = p.id
            WHERE p.reservation_id = #{reservationId}
              AND p.status IN ('PUBLISHED', 'CONFIRMED')
            ORDER BY p.plan_version DESC
            LIMIT 1
            """)
    Optional<PlanRow> findCurrentPlan(@Param("reservationId") UUID reservationId);

    @Select("""
            SELECT p.id, p.plan_no, p.reservation_id, p.provider_id, p.status,
                   p.plan_version, p.valid_until, p.service_date, p.summary,
                   p.total_amount_minor, p.currency, p.published_at,
                   p.confirmed_at, o.id service_order_id, p.version
            FROM inspection_service_plan p
            LEFT JOIN inspection_service_order o ON o.plan_id = p.id
            WHERE p.id = #{planId}
            """)
    Optional<PlanRow> findPlan(@Param("planId") UUID planId);

    @Select("""
            SELECT item_code code, item_name name, quantity, unit,
                   unit_price_minor, line_amount_minor
            FROM inspection_service_plan_item
            WHERE plan_id = #{planId}
            ORDER BY display_order, id
            """)
    List<PlanItemRow> findPlanItems(@Param("planId") UUID planId);

    @Select("""
            SELECT deliverable_code code, deliverable_name label
            FROM inspection_plan_deliverable
            WHERE plan_id = #{planId}
            ORDER BY display_order, id
            """)
    List<CodeLabelRow> findPlanDeliverables(@Param("planId") UUID planId);

    @Select("""
            SELECT id, service_order_no, reservation_id, plan_id, customer_id,
                   provider_id, status, created_at, version
            FROM inspection_service_order
            WHERE id = #{orderId}
            """)
    Optional<ServiceOrderRow> findOrder(@Param("orderId") UUID orderId);

    @Select("""
            SELECT request_hash request_digest, resource_id
            FROM inspection_idempotency_record
            WHERE actor_id = #{actorId}
              AND operation_code = #{operation}
              AND idempotency_key = #{key}
            """)
    Optional<IdempotencyRow> findIdempotency(
            @Param("actorId") UUID actorId,
            @Param("operation") String operation,
            @Param("key") UUID key);

    @Select("""
            SELECT COALESCE(MAX(plan_version), 0) + 1
            FROM inspection_service_plan
            WHERE reservation_id = #{reservationId}
            """)
    int nextPlanVersion(@Param("reservationId") UUID reservationId);

    @Insert("""
            INSERT INTO inspection_idempotency_record (
                id, actor_id, operation_code, idempotency_key, request_hash,
                resource_type, resource_id, response_status, response_body, expires_at
            )
            VALUES (
                gen_random_uuid(), #{actorId}, #{operation}, #{key}, #{digest},
                #{resourceType}, #{resourceId}, #{responseStatus}, '{}'::jsonb,
                now() + interval '24 hours'
            )
            ON CONFLICT (actor_id, operation_code, idempotency_key) DO NOTHING
            """)
    int insertIdempotency(
            @Param("actorId") UUID actorId,
            @Param("operation") String operation,
            @Param("key") UUID key,
            @Param("digest") String digest,
            @Param("resourceType") String resourceType,
            @Param("resourceId") UUID resourceId,
            @Param("responseStatus") int responseStatus);

    @Update("""
            UPDATE inspection_reservation
            SET status = 'PLAN_PUBLISHED',
                version = version + 1,
                updated_by = #{actorId},
                updated_at = now()
            WHERE id = #{reservationId}
              AND version = #{expectedVersion}
              AND status IN ('SUBMITTED', 'PLAN_PUBLISHED')
              AND NOT EXISTS (
                  SELECT 1
                  FROM inspection_service_plan
                  WHERE reservation_id = #{reservationId}
                    AND status = 'CONFIRMED'
              )
            """)
    int advanceReservationForPlan(
            @Param("reservationId") UUID reservationId,
            @Param("expectedVersion") int expectedVersion,
            @Param("actorId") UUID actorId);

    @Update("""
            UPDATE inspection_service_plan
            SET status = 'WITHDRAWN',
                version = version + 1,
                updated_by = #{actorId},
                updated_at = now()
            WHERE reservation_id = #{reservationId}
              AND status = 'PUBLISHED'
            """)
    int withdrawPublishedPlan(
            @Param("reservationId") UUID reservationId,
            @Param("actorId") UUID actorId);

    @Insert("""
            INSERT INTO inspection_service_plan (
                id, plan_no, reservation_id, provider_id, plan_version, status,
                summary, service_date, valid_until, currency, total_amount_minor,
                published_at, version, created_by, updated_by, created_at, updated_at
            )
            VALUES (
                #{id}, #{planNo}, #{reservationId}, #{providerId}, #{planVersion},
                'PUBLISHED', #{summary}, #{serviceDate}, #{validUntil}, 'CNY',
                #{totalAmountMinor}, #{publishedAt}, #{version}, #{actorId},
                #{actorId}, #{publishedAt}, #{publishedAt}
            )
            """)
    void insertPlan(
            @Param("id") UUID id,
            @Param("planNo") String planNo,
            @Param("reservationId") UUID reservationId,
            @Param("providerId") UUID providerId,
            @Param("planVersion") int planVersion,
            @Param("summary") String summary,
            @Param("serviceDate") LocalDate serviceDate,
            @Param("validUntil") Instant validUntil,
            @Param("totalAmountMinor") long totalAmountMinor,
            @Param("publishedAt") Instant publishedAt,
            @Param("version") int version,
            @Param("actorId") UUID actorId);

    @Insert("""
            INSERT INTO inspection_service_plan_item (
                id, plan_id, item_code, item_name, quantity, unit,
                unit_price_minor, line_amount_minor, display_order
            )
            VALUES (
                gen_random_uuid(), #{planId}, #{code}, #{name}, #{quantity},
                #{unit}, #{unitPriceMinor}, #{lineAmountMinor}, #{displayOrder}
            )
            """)
    void insertPlanItem(
            @Param("planId") UUID planId,
            @Param("code") String code,
            @Param("name") String name,
            @Param("quantity") int quantity,
            @Param("unit") String unit,
            @Param("unitPriceMinor") long unitPriceMinor,
            @Param("lineAmountMinor") long lineAmountMinor,
            @Param("displayOrder") int displayOrder);

    @Insert("""
            INSERT INTO inspection_plan_deliverable (
                id, plan_id, deliverable_code, deliverable_name, display_order
            )
            VALUES (
                gen_random_uuid(), #{planId}, #{code}, #{label}, #{displayOrder}
            )
            """)
    void insertPlanDeliverable(
            @Param("planId") UUID planId,
            @Param("code") String code,
            @Param("label") String label,
            @Param("displayOrder") int displayOrder);

    @Update("""
            UPDATE inspection_service_plan
            SET status = 'CONFIRMED',
                confirmed_at = #{confirmedAt},
                agreement_version = #{agreementVersion},
                version = version + 1,
                updated_by = #{actorId},
                updated_at = #{confirmedAt}
            WHERE id = #{planId}
              AND status = 'PUBLISHED'
              AND version = #{expectedVersion}
              AND valid_until > #{confirmedAt}
            """)
    int confirmPlan(
            @Param("planId") UUID planId,
            @Param("expectedVersion") int expectedVersion,
            @Param("agreementVersion") String agreementVersion,
            @Param("confirmedAt") Instant confirmedAt,
            @Param("actorId") UUID actorId);

    @Insert("""
            INSERT INTO inspection_service_order (
                id, service_order_no, reservation_id, plan_id, customer_id,
                provider_id, status, service_snapshot, version, created_by,
                updated_by, created_at, updated_at
            )
            SELECT
                #{id}, #{serviceOrderNo}, r.id, p.id, r.customer_id,
                p.provider_id, 'ARRANGING',
                jsonb_build_object(
                    'serviceId', r.service_id,
                    'serviceTitle', r.service_title_snapshot,
                    'serviceCoverUrl', r.service_cover_url_snapshot,
                    'cityCode', r.city_code,
                    'location', jsonb_build_object(
                        'addressCiphertext', encode(r.address_ciphertext, 'base64'),
                        'longitude', ST_X(r.location::geometry),
                        'latitude', ST_Y(r.location::geometry)
                    ),
                    'lineLengthMeters', r.line_length_meters,
                    'serviceDate', p.service_date,
                    'inspectionItems', COALESCE((
                        SELECT jsonb_agg(
                            jsonb_build_object(
                                'code', i.item_code,
                                'label', i.item_name_snapshot
                            )
                            ORDER BY i.display_order, i.id
                        )
                        FROM inspection_reservation_item i
                        WHERE i.reservation_id = r.id
                    ), '[]'::jsonb)
                ),
                0, #{actorId}, #{actorId}, #{createdAt}, #{createdAt}
            FROM inspection_service_plan p
            JOIN inspection_reservation r ON r.id = p.reservation_id
            WHERE p.id = #{planId}
            """)
    int insertServiceOrder(
            @Param("id") UUID id,
            @Param("serviceOrderNo") String serviceOrderNo,
            @Param("planId") UUID planId,
            @Param("actorId") UUID actorId,
            @Param("createdAt") Instant createdAt);

    @Insert("""
            INSERT INTO inspection_state_transition (
                id, aggregate_type, aggregate_id, from_status, to_status,
                actor_type, actor_id, request_id, occurred_at
            )
            VALUES (
                gen_random_uuid(), #{aggregateType}, #{aggregateId},
                #{fromStatus}, #{toStatus}, #{actorType}, #{actorId},
                #{requestId}, #{occurredAt}
            )
            """)
    void insertTransition(
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") UUID aggregateId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("actorType") String actorType,
            @Param("actorId") UUID actorId,
            @Param("requestId") String requestId,
            @Param("occurredAt") Instant occurredAt);

    @Insert("""
            INSERT INTO inspection_domain_event_outbox (
                id, aggregate_type, aggregate_id, event_type, event_payload,
                occurred_at, next_attempt_at
            )
            VALUES (
                gen_random_uuid(), #{aggregateType}, #{aggregateId}, #{eventType},
                jsonb_build_object('aggregateId', CAST(#{aggregateId} AS text)),
                #{occurredAt}, #{occurredAt}
            )
            """)
    void insertOutbox(
            @Param("aggregateType") String aggregateType,
            @Param("aggregateId") UUID aggregateId,
            @Param("eventType") String eventType,
            @Param("occurredAt") Instant occurredAt);

    record ReservationRow(
            UUID id,
            String reservationNo,
            UUID customerId,
            UUID providerId,
            String status,
            UUID serviceId,
            String serviceTitle,
            String serviceCoverUrl,
            String cityCode,
            byte[] addressCiphertext,
            Double longitude,
            Double latitude,
            Long lineLengthMeters,
            int version
    ) {
    }

    record PlanRow(
            UUID id,
            String planNo,
            UUID reservationId,
            UUID providerId,
            String status,
            int planVersion,
            Instant validUntil,
            LocalDate serviceDate,
            String summary,
            long totalAmountMinor,
            String currency,
            Instant publishedAt,
            Instant confirmedAt,
            UUID serviceOrderId,
            int version
    ) {
    }

    record PlanItemRow(
            String code,
            String name,
            int quantity,
            String unit,
            long unitPriceMinor,
            long lineAmountMinor
    ) {
    }

    record CodeLabelRow(String code, String label) {
    }

    record ServiceOrderRow(
            UUID id,
            String serviceOrderNo,
            UUID reservationId,
            UUID planId,
            UUID customerId,
            UUID providerId,
            String status,
            Instant createdAt,
            int version
    ) {
    }

    record IdempotencyRow(String requestDigest, UUID resourceId) {
    }
}
