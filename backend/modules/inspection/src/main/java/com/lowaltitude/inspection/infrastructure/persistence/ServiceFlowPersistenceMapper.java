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
                   provider_id, status, planned_start_at, created_at,
                   delivered_at, accepted_at, version
            FROM inspection_service_order
            WHERE id = #{orderId}
            """)
    Optional<ServiceOrderRow> findOrder(@Param("orderId") UUID orderId);

    @Select("""
            SELECT id, service_order_id, status, tower_count,
                   inspected_length_meters, issue_count, published_at,
                   accepted_at, version
            FROM inspection_delivery
            WHERE id = #{deliveryId}
            """)
    Optional<DeliveryRow> findDelivery(@Param("deliveryId") UUID deliveryId);

    @Select("""
            SELECT id, service_order_id, status, tower_count,
                   inspected_length_meters, issue_count, published_at,
                   accepted_at, version
            FROM inspection_delivery
            WHERE service_order_id = #{orderId}
            """)
    Optional<DeliveryRow> findDeliveryByOrder(@Param("orderId") UUID orderId);

    @Select("""
            SELECT item->>'severityCode' severity_code,
                   item->>'severityName' severity_name,
                   CAST(item->>'count' AS integer) count
            FROM inspection_delivery d
            CROSS JOIN LATERAL jsonb_array_elements(d.issue_summary) item
            WHERE d.id = #{deliveryId}
            """)
    List<DeliveryIssueRow> findDeliveryIssues(
            @Param("deliveryId") UUID deliveryId);

    @Select("""
            SELECT df.file_id, df.file_type type, df.display_name,
                   df.point_count, f.content_type, f.size_bytes,
                   f.file_status status, f.bucket_name bucket, f.object_key
            FROM inspection_delivery_file df
            JOIN inspection_file_object f ON f.id = df.file_id
            WHERE df.delivery_id = #{deliveryId}
            ORDER BY df.display_order, df.id
            """)
    List<DeliveryFileRow> findDeliveryFiles(
            @Param("deliveryId") UUID deliveryId);

    @Select("""
            SELECT id, owner_id service_order_id, provider_id, extension,
                   content_type, size_bytes, bucket_name bucket, object_key,
                   file_status status
            FROM inspection_file_object
            WHERE id = #{fileId}
              AND purpose = 'DELIVERY_ARTIFACT'
            """)
    Optional<DeliveryFileObjectRow> findDeliveryFileObject(
            @Param("fileId") UUID fileId);

    @Select("""
            SELECT a.id, a.service_order_id, a.delivery_id, a.accepted_by,
                   a.accepted_at, a.note_ciphertext, o.version order_version
            FROM inspection_acceptance a
            JOIN inspection_service_order o ON o.id = a.service_order_id
            WHERE a.id = #{acceptanceId}
            """)
    Optional<AcceptanceRow> findAcceptance(
            @Param("acceptanceId") UUID acceptanceId);

    @Select("""
            SELECT a.id, a.service_order_id, a.delivery_id, a.accepted_by,
                   a.accepted_at, a.note_ciphertext, o.version order_version
            FROM inspection_acceptance a
            JOIN inspection_service_order o ON o.id = a.service_order_id
            WHERE a.service_order_id = #{orderId}
            """)
    Optional<AcceptanceRow> findAcceptanceByOrder(
            @Param("orderId") UUID orderId);

    @Select("""
            SELECT assignment_type type, resource_id,
                   display_name_snapshot display_name, role_label
            FROM inspection_order_assignment
            WHERE service_order_id = #{orderId}
            ORDER BY assigned_at, id
            """)
    List<OrderAssignmentRow> findOrderAssignments(@Param("orderId") UUID orderId);

    @Select("""
            SELECT id, event_code, occurred_at, message
            FROM inspection_order_progress
            WHERE service_order_id = #{orderId}
            ORDER BY occurred_at, id
            """)
    List<OrderProgressRow> findOrderProgress(@Param("orderId") UUID orderId);

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

    @Update("""
            UPDATE inspection_service_order
            SET planned_start_at = #{plannedStartAt},
                version = version + 1,
                updated_by = #{actorId},
                updated_at = #{updatedAt}
            WHERE id = #{orderId}
              AND status = 'ARRANGING'
              AND version = #{expectedVersion}
            """)
    int updateOrderArrangement(
            @Param("orderId") UUID orderId,
            @Param("expectedVersion") int expectedVersion,
            @Param("plannedStartAt") Instant plannedStartAt,
            @Param("actorId") UUID actorId,
            @Param("updatedAt") Instant updatedAt);

    @Delete("""
            DELETE FROM inspection_order_assignment
            WHERE service_order_id = #{orderId}
            """)
    void deleteOrderAssignments(@Param("orderId") UUID orderId);

    @Insert("""
            INSERT INTO inspection_order_assignment (
                id, service_order_id, assignment_type, resource_id,
                display_name_snapshot, role_label, assigned_by, assigned_at
            )
            VALUES (
                gen_random_uuid(), #{orderId}, #{type}, #{resourceId},
                #{displayName}, #{roleLabel}, #{actorId}, #{assignedAt}
            )
            """)
    void insertOrderAssignment(
            @Param("orderId") UUID orderId,
            @Param("type") String type,
            @Param("resourceId") UUID resourceId,
            @Param("displayName") String displayName,
            @Param("roleLabel") String roleLabel,
            @Param("actorId") UUID actorId,
            @Param("assignedAt") Instant assignedAt);

    @Update("""
            UPDATE inspection_service_order
            SET status = #{nextStatus},
                started_at = CASE
                    WHEN #{eventCode} = 'SERVICE_STARTED' THEN #{occurredAt}
                    ELSE started_at
                END,
                version = version + 1,
                updated_by = #{actorId},
                updated_at = #{updatedAt}
            WHERE id = #{orderId}
              AND status = #{expectedStatus}
              AND version = #{expectedVersion}
            """)
    int updateOrderProgress(
            @Param("orderId") UUID orderId,
            @Param("expectedStatus") String expectedStatus,
            @Param("nextStatus") String nextStatus,
            @Param("expectedVersion") int expectedVersion,
            @Param("eventCode") String eventCode,
            @Param("occurredAt") Instant occurredAt,
            @Param("actorId") UUID actorId,
            @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE inspection_service_order
            SET status = 'DELIVERED',
                delivered_at = #{deliveredAt},
                version = version + 1,
                updated_by = #{actorId},
                updated_at = #{deliveredAt}
            WHERE id = #{orderId}
              AND status = 'IN_PROGRESS'
              AND version = #{expectedVersion}
            """)
    int updateOrderDelivered(
            @Param("orderId") UUID orderId,
            @Param("expectedVersion") int expectedVersion,
            @Param("deliveredAt") Instant deliveredAt,
            @Param("actorId") UUID actorId);

    @Insert("""
            INSERT INTO inspection_delivery (
                id, service_order_id, status, tower_count,
                inspected_length_meters, issue_count, issue_summary,
                published_by, published_at, version, created_at, updated_at
            )
            VALUES (
                #{id}, #{orderId}, 'PUBLISHED', #{towerCount},
                #{inspectedLengthMeters}, #{issueCount},
                CAST(#{issueSummaryJson} AS jsonb), #{actorId},
                #{publishedAt}, #{version}, #{publishedAt}, #{publishedAt}
            )
            """)
    void insertDelivery(
            @Param("id") UUID id,
            @Param("orderId") UUID orderId,
            @Param("towerCount") int towerCount,
            @Param("inspectedLengthMeters") long inspectedLengthMeters,
            @Param("issueCount") int issueCount,
            @Param("issueSummaryJson") String issueSummaryJson,
            @Param("actorId") UUID actorId,
            @Param("publishedAt") Instant publishedAt,
            @Param("version") int version);

    @Insert("""
            INSERT INTO inspection_delivery_file (
                id, delivery_id, file_id, file_type, display_name,
                point_count, display_order, created_at
            )
            VALUES (
                gen_random_uuid(), #{deliveryId}, #{fileId}, #{type},
                #{displayName}, #{pointCount}, #{displayOrder}, #{createdAt}
            )
            """)
    void insertDeliveryFile(
            @Param("deliveryId") UUID deliveryId,
            @Param("fileId") UUID fileId,
            @Param("type") String type,
            @Param("displayName") String displayName,
            @Param("pointCount") Integer pointCount,
            @Param("displayOrder") int displayOrder,
            @Param("createdAt") Instant createdAt);

    @Update("""
            UPDATE inspection_service_order
            SET status = 'ACCEPTED',
                accepted_at = #{acceptedAt},
                version = version + 1,
                updated_by = #{actorId},
                updated_at = #{acceptedAt}
            WHERE id = #{orderId}
              AND status = 'DELIVERED'
              AND version = #{expectedVersion}
            """)
    int updateOrderAccepted(
            @Param("orderId") UUID orderId,
            @Param("expectedVersion") int expectedVersion,
            @Param("acceptedAt") Instant acceptedAt,
            @Param("actorId") UUID actorId);

    @Update("""
            UPDATE inspection_delivery
            SET status = 'ACCEPTED',
                accepted_at = #{acceptedAt},
                version = version + 1,
                updated_at = #{acceptedAt}
            WHERE id = #{deliveryId}
              AND status = 'PUBLISHED'
              AND version = #{expectedVersion}
            """)
    int updateDeliveryAccepted(
            @Param("deliveryId") UUID deliveryId,
            @Param("expectedVersion") int expectedVersion,
            @Param("acceptedAt") Instant acceptedAt);

    @Insert("""
            INSERT INTO inspection_acceptance (
                id, service_order_id, delivery_id, accepted_by,
                accepted_at, note_ciphertext, idempotency_key, created_at
            )
            VALUES (
                #{id}, #{orderId}, #{deliveryId}, #{acceptedBy},
                #{acceptedAt}, #{noteCiphertext}, #{idempotencyKey}, #{acceptedAt}
            )
            """)
    void insertAcceptance(
            @Param("id") UUID id,
            @Param("orderId") UUID orderId,
            @Param("deliveryId") UUID deliveryId,
            @Param("acceptedBy") UUID acceptedBy,
            @Param("acceptedAt") Instant acceptedAt,
            @Param("noteCiphertext") byte[] noteCiphertext,
            @Param("idempotencyKey") UUID idempotencyKey);

    @Insert("""
            INSERT INTO inspection_order_progress (
                id, service_order_id, event_code, occurred_at,
                message, operator_id, created_at
            )
            VALUES (
                #{id}, #{orderId}, #{eventCode}, #{occurredAt},
                #{message}, #{actorId}, #{createdAt}
            )
            """)
    void insertOrderProgress(
            @Param("id") UUID id,
            @Param("orderId") UUID orderId,
            @Param("eventCode") String eventCode,
            @Param("occurredAt") Instant occurredAt,
            @Param("message") String message,
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
            Instant plannedStartAt,
            Instant createdAt,
            Instant deliveredAt,
            Instant acceptedAt,
            int version
    ) {
    }

    record OrderAssignmentRow(
            String type,
            UUID resourceId,
            String displayName,
            String roleLabel
    ) {
    }

    record OrderProgressRow(
            UUID id,
            String eventCode,
            Instant occurredAt,
            String message
    ) {
    }

    record DeliveryRow(
            UUID id,
            UUID serviceOrderId,
            String status,
            int towerCount,
            long inspectedLengthMeters,
            int issueCount,
            Instant publishedAt,
            Instant acceptedAt,
            int version
    ) {
    }

    record DeliveryIssueRow(String severityCode, String severityName, int count) {
    }

    record DeliveryFileRow(
            UUID fileId,
            String type,
            String displayName,
            Integer pointCount,
            String contentType,
            long sizeBytes,
            String status,
            String bucket,
            String objectKey
    ) {
    }

    record DeliveryFileObjectRow(
            UUID id,
            UUID serviceOrderId,
            UUID providerId,
            String extension,
            String contentType,
            long sizeBytes,
            String bucket,
            String objectKey,
            String status
    ) {
    }

    record AcceptanceRow(
            UUID id,
            UUID serviceOrderId,
            UUID deliveryId,
            UUID acceptedBy,
            Instant acceptedAt,
            byte[] noteCiphertext,
            int orderVersion
    ) {
    }

    record IdempotencyRow(String requestDigest, UUID resourceId) {
    }
}
