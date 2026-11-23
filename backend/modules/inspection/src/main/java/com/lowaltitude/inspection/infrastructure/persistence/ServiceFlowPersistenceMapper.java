package com.lowaltitude.inspection.infrastructure.persistence;

import java.math.BigDecimal;
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
                   p.scope_summary, p.report_due_hours, p.media_retention_days,
                   p.change_note, p.total_amount_minor, p.currency, p.published_at,
                   p.confirmed_at, p.confirmed_by, o.id service_order_id, p.version
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
                   p.scope_summary, p.report_due_hours, p.media_retention_days,
                   p.change_note, p.total_amount_minor, p.currency, p.published_at,
                   p.confirmed_at, p.confirmed_by, o.id service_order_id, p.version
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
            SELECT deliverable_code code, deliverable_name label, file_type
            FROM inspection_plan_deliverable
            WHERE plan_id = #{planId}
            ORDER BY display_order, id
            """)
    List<PlanDeliverableRow> findPlanDeliverables(@Param("planId") UUID planId);

    @Select("""
            SELECT visit_no, planned_date
            FROM inspection_plan_visit
            WHERE plan_id = #{planId}
            ORDER BY visit_no
            """)
    List<PlanVisitRow> findPlanVisits(@Param("planId") UUID planId);

    @Select("""
            SELECT item->>'code' code, item->>'label' label
            FROM inspection_service_plan p
            CROSS JOIN LATERAL jsonb_array_elements(p.service_methods) item
            WHERE p.id = #{planId}
            """)
    List<CodeLabelRow> findPlanServiceMethods(@Param("planId") UUID planId);

    @Select("""
            SELECT node_no, node_type, visit_no, amount_minor
            FROM inspection_plan_payment_node
            WHERE plan_id = #{planId}
            ORDER BY node_no
            """)
    List<PlanPaymentNodeRow> findPlanPaymentNodes(@Param("planId") UUID planId);

    @Select("""
            SELECT id, plan_no, plan_version, status, total_amount_minor,
                   change_note, published_at
            FROM inspection_service_plan
            WHERE reservation_id = #{reservationId}
            ORDER BY plan_version
            """)
    List<PlanVersionRow> findPlanVersions(@Param("reservationId") UUID reservationId);

    @Select("""
            SELECT o.id provider_id, o.organization_name name, pp.certification_label,
                   o.qualification_reviewed_at IS NOT NULL qualification_reviewed
            FROM identity_organization o
            LEFT JOIN inspection_provider_profile pp ON pp.provider_id = o.id
            WHERE o.id = #{providerId}
            """)
    Optional<ProviderRow> findProvider(@Param("providerId") UUID providerId);

    @Select("""
            SELECT o.organization_name, u.display_name operator_name
            FROM inspection_reservation r
            JOIN identity_user u ON u.id = r.customer_id
            LEFT JOIN identity_organization o ON o.id = r.customer_organization_id
            WHERE r.id = #{reservationId}
            """)
    Optional<CustomerPartyRow> findCustomerParty(
            @Param("reservationId") UUID reservationId);

    @Select("SELECT display_name FROM identity_user WHERE id = #{subjectId}")
    Optional<String> findDisplayName(@Param("subjectId") UUID subjectId);

    @Select("""
            SELECT id, service_order_no, reservation_id, plan_id, customer_id,
                   provider_id, payment_mode, status, created_at,
                   delivered_at, accepted_at, version
            FROM inspection_service_order
            WHERE id = #{orderId}
            """)
    Optional<ServiceOrderRow> findOrder(@Param("orderId") UUID orderId);

    @Select("SELECT id FROM inspection_service_order WHERE id = #{orderId} FOR UPDATE")
    Optional<UUID> lockOrder(@Param("orderId") UUID orderId);

    @Select("""
            SELECT id, service_order_id, visit_no, planned_date, planned_start_at,
                   status, started_at, delivered_at, accepted_at, version
            FROM inspection_service_visit
            WHERE service_order_id = #{orderId}
            ORDER BY visit_no
            """)
    List<ServiceVisitRow> findOrderVisits(@Param("orderId") UUID orderId);

    @Select("""
            SELECT id, service_order_id, visit_no, planned_date, planned_start_at,
                   status, started_at, delivered_at, accepted_at, version
            FROM inspection_service_visit
            WHERE id = #{visitId}
            """)
    Optional<ServiceVisitRow> findVisit(@Param("visitId") UUID visitId);

    @Select("""
            SELECT n.node_no, n.node_type, v.visit_no, n.visit_id, n.amount_minor, n.paid_at
            FROM inspection_order_payment_node n
            LEFT JOIN inspection_service_visit v ON v.id = n.visit_id
            WHERE n.service_order_id = #{orderId}
            ORDER BY n.node_no
            """)
    List<OrderPaymentNodeRow> findOrderPaymentNodes(@Param("orderId") UUID orderId);

    @Select("""
            SELECT id, service_order_id, visit_id, status, tower_count,
                   inspected_length_meters, issue_count, issue_group_by,
                   published_at, accepted_at, version
            FROM inspection_delivery
            WHERE id = #{deliveryId}
            """)
    Optional<DeliveryRow> findDelivery(@Param("deliveryId") UUID deliveryId);

    @Select("""
            SELECT id, service_order_id, visit_id, status, tower_count,
                   inspected_length_meters, issue_count, issue_group_by,
                   published_at, accepted_at, version
            FROM inspection_delivery
            WHERE visit_id = #{visitId}
            """)
    Optional<DeliveryRow> findDeliveryByVisit(@Param("visitId") UUID visitId);

    @Select("""
            SELECT item->>'groupCode' group_code,
                   item->>'groupName' group_name,
                   CAST(item->>'count' AS integer) count
            FROM inspection_delivery d
            CROSS JOIN LATERAL jsonb_array_elements(d.issue_summary) item
            WHERE d.id = #{deliveryId}
            """)
    List<DeliveryIssueRow> findDeliveryIssues(
            @Param("deliveryId") UUID deliveryId);

    @Select("""
            SELECT item->>'code' code,
                   item->>'label' label,
                   CAST(item->>'value' AS numeric) metric_value,
                   item->>'unit' unit
            FROM inspection_delivery d
            CROSS JOIN LATERAL jsonb_array_elements(d.metrics) item
            WHERE d.id = #{deliveryId}
            """)
    List<DeliveryMetricRow> findDeliveryMetrics(
            @Param("deliveryId") UUID deliveryId);

    @Select("""
            SELECT df.file_id, df.file_type type, df.display_name,
                   df.point_count, df.item_count, f.content_type, f.size_bytes,
                   f.file_status status, f.bucket_name bucket, f.object_key
            FROM inspection_delivery_file df
            JOIN inspection_file_object f ON f.id = df.file_id
            WHERE df.delivery_id = #{deliveryId}
            ORDER BY df.display_order, df.id
            """)
    List<DeliveryFileRow> findDeliveryFiles(
            @Param("deliveryId") UUID deliveryId);

    @Select("""
            SELECT id, owner_id visit_id, provider_id, extension,
                   content_type, size_bytes, bucket_name bucket, object_key,
                   file_status status
            FROM inspection_file_object
            WHERE id = #{fileId}
              AND purpose = 'DELIVERY_ARTIFACT'
            """)
    Optional<DeliveryFileObjectRow> findDeliveryFileObject(
            @Param("fileId") UUID fileId);

    @Select("""
            SELECT a.id, a.service_order_id, a.visit_id, a.delivery_id, a.accepted_by,
                   u.display_name accepted_by_name, a.accepted_at, a.note_ciphertext,
                   v.version visit_version, o.status order_status
            FROM inspection_acceptance a
            JOIN inspection_service_order o ON o.id = a.service_order_id
            JOIN inspection_service_visit v ON v.id = a.visit_id
            LEFT JOIN identity_user u ON u.id = a.accepted_by
            WHERE a.id = #{acceptanceId}
            """)
    Optional<AcceptanceRow> findAcceptance(
            @Param("acceptanceId") UUID acceptanceId);

    @Select("""
            SELECT a.id, a.service_order_id, a.visit_id, a.delivery_id, a.accepted_by,
                   u.display_name accepted_by_name, a.accepted_at, a.note_ciphertext,
                   v.version visit_version, o.status order_status
            FROM inspection_acceptance a
            JOIN inspection_service_order o ON o.id = a.service_order_id
            JOIN inspection_service_visit v ON v.id = a.visit_id
            LEFT JOIN identity_user u ON u.id = a.accepted_by
            WHERE a.visit_id = #{visitId}
            """)
    Optional<AcceptanceRow> findAcceptanceByVisit(
            @Param("visitId") UUID visitId);

    @Select("""
            SELECT assignment_type type, resource_id,
                   display_name_snapshot display_name, role_label,
                   contact_mobile_masked, certified_pilot
            FROM inspection_order_assignment
            WHERE service_order_id = #{orderId}
            ORDER BY assigned_at, id
            """)
    List<OrderAssignmentRow> findOrderAssignments(@Param("orderId") UUID orderId);

    @Select("""
            SELECT id, event_code, occurred_at, message
            FROM inspection_order_progress
            WHERE service_order_id = #{orderId}
              AND visit_id IS NULL
            ORDER BY occurred_at, id
            """)
    List<OrderProgressRow> findOrderProgress(@Param("orderId") UUID orderId);

    @Select("""
            SELECT id, event_code, occurred_at, message
            FROM inspection_order_progress
            WHERE visit_id = #{visitId}
            ORDER BY occurred_at, id
            """)
    List<OrderProgressRow> findVisitProgress(@Param("visitId") UUID visitId);

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
                summary, scope_summary, service_methods, report_due_hours,
                media_retention_days, change_note, service_date, valid_until,
                currency, total_amount_minor, published_at, version, created_by,
                updated_by, created_at, updated_at
            )
            VALUES (
                #{id}, #{planNo}, #{reservationId}, #{providerId}, #{planVersion},
                'PUBLISHED', #{summary}, #{scopeSummary},
                CAST(#{serviceMethodsJson} AS jsonb), #{reportDueHours},
                #{mediaRetentionDays}, #{changeNote}, #{serviceDate}, #{validUntil},
                'CNY', #{totalAmountMinor}, #{publishedAt}, #{version}, #{actorId},
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
            @Param("scopeSummary") String scopeSummary,
            @Param("serviceMethodsJson") String serviceMethodsJson,
            @Param("reportDueHours") Integer reportDueHours,
            @Param("mediaRetentionDays") Integer mediaRetentionDays,
            @Param("changeNote") String changeNote,
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
                id, plan_id, deliverable_code, deliverable_name, file_type, display_order
            )
            VALUES (
                gen_random_uuid(), #{planId}, #{code}, #{label}, #{fileType}, #{displayOrder}
            )
            """)
    void insertPlanDeliverable(
            @Param("planId") UUID planId,
            @Param("code") String code,
            @Param("label") String label,
            @Param("fileType") String fileType,
            @Param("displayOrder") int displayOrder);

    @Insert("""
            INSERT INTO inspection_plan_visit (id, plan_id, visit_no, planned_date)
            VALUES (gen_random_uuid(), #{planId}, #{visitNo}, #{plannedDate})
            """)
    void insertPlanVisit(
            @Param("planId") UUID planId,
            @Param("visitNo") int visitNo,
            @Param("plannedDate") LocalDate plannedDate);

    @Insert("""
            INSERT INTO inspection_plan_payment_node (
                id, plan_id, node_no, node_type, visit_no, amount_minor
            )
            VALUES (
                gen_random_uuid(), #{planId}, #{nodeNo}, #{nodeType}, #{visitNo},
                #{amountMinor}
            )
            """)
    void insertPlanPaymentNode(
            @Param("planId") UUID planId,
            @Param("nodeNo") int nodeNo,
            @Param("nodeType") String nodeType,
            @Param("visitNo") Integer visitNo,
            @Param("amountMinor") long amountMinor);

    @Update("""
            UPDATE inspection_service_plan
            SET status = 'CONFIRMED',
                confirmed_at = #{confirmedAt},
                confirmed_by = #{actorId},
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
                provider_id, payment_mode, status, service_snapshot, version,
                created_by, updated_by, created_at, updated_at
            )
            SELECT
                #{id}, #{serviceOrderNo}, r.id, p.id, r.customer_id,
                p.provider_id, #{paymentMode}, #{status},
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
            @Param("paymentMode") String paymentMode,
            @Param("status") String status,
            @Param("actorId") UUID actorId,
            @Param("createdAt") Instant createdAt);

    @Insert("""
            INSERT INTO inspection_service_visit (
                id, service_order_id, visit_no, planned_date, status, version,
                created_by, updated_by, created_at, updated_at
            )
            VALUES (
                #{id}, #{orderId}, #{visitNo}, #{plannedDate}, #{status}, 0,
                #{actorId}, #{actorId}, #{createdAt}, #{createdAt}
            )
            """)
    void insertServiceVisit(
            @Param("id") UUID id,
            @Param("orderId") UUID orderId,
            @Param("visitNo") int visitNo,
            @Param("plannedDate") LocalDate plannedDate,
            @Param("status") String status,
            @Param("actorId") UUID actorId,
            @Param("createdAt") Instant createdAt);

    @Insert("""
            INSERT INTO inspection_order_payment_node (
                id, service_order_id, node_no, node_type, visit_id, amount_minor,
                paid_at, created_at, updated_at
            )
            VALUES (
                gen_random_uuid(), #{orderId}, #{nodeNo}, #{nodeType}, #{visitId},
                #{amountMinor}, #{paidAt}, #{createdAt}, #{createdAt}
            )
            """)
    void insertOrderPaymentNode(
            @Param("orderId") UUID orderId,
            @Param("nodeNo") int nodeNo,
            @Param("nodeType") String nodeType,
            @Param("visitId") UUID visitId,
            @Param("amountMinor") long amountMinor,
            @Param("paidAt") Instant paidAt,
            @Param("createdAt") Instant createdAt);

    @Update("""
            UPDATE inspection_service_order
            SET version = version + 1,
                updated_by = #{actorId},
                updated_at = #{updatedAt}
            WHERE id = #{orderId}
              AND status IN ('ARRANGING', 'IN_PROGRESS', 'DELIVERED')
              AND version = #{expectedVersion}
            """)
    int updateOrderArrangement(
            @Param("orderId") UUID orderId,
            @Param("expectedVersion") int expectedVersion,
            @Param("actorId") UUID actorId,
            @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE inspection_service_visit
            SET status = 'SCHEDULED',
                planned_start_at = #{plannedStartAt},
                version = version + 1,
                updated_by = #{actorId},
                updated_at = #{updatedAt}
            WHERE id = #{visitId}
              AND status IN ('PENDING_SCHEDULE', 'SCHEDULED')
              AND version = #{expectedVersion}
            """)
    int updateVisitSchedule(
            @Param("visitId") UUID visitId,
            @Param("expectedVersion") int expectedVersion,
            @Param("plannedStartAt") Instant plannedStartAt,
            @Param("actorId") UUID actorId,
            @Param("updatedAt") Instant updatedAt);

    @Update("""
            UPDATE inspection_service_visit
            SET status = #{nextStatus},
                started_at = CASE
                    WHEN #{eventCode} = 'SERVICE_STARTED' THEN #{occurredAt}
                    ELSE started_at
                END,
                version = version + 1,
                updated_by = #{actorId},
                updated_at = #{occurredAt}
            WHERE id = #{visitId}
              AND status = #{expectedStatus}
              AND version = #{expectedVersion}
            """)
    int updateVisitProgress(
            @Param("visitId") UUID visitId,
            @Param("expectedStatus") String expectedStatus,
            @Param("nextStatus") String nextStatus,
            @Param("expectedVersion") int expectedVersion,
            @Param("eventCode") String eventCode,
            @Param("occurredAt") Instant occurredAt,
            @Param("actorId") UUID actorId);

    @Update("""
            UPDATE inspection_service_visit
            SET status = 'DELIVERED',
                delivered_at = #{deliveredAt},
                version = version + 1,
                updated_by = #{actorId},
                updated_at = #{deliveredAt}
            WHERE id = #{visitId}
              AND status = 'IN_PROGRESS'
              AND version = #{expectedVersion}
            """)
    int updateVisitDelivered(
            @Param("visitId") UUID visitId,
            @Param("expectedVersion") int expectedVersion,
            @Param("deliveredAt") Instant deliveredAt,
            @Param("actorId") UUID actorId);

    @Update("""
            UPDATE inspection_service_visit
            SET status = 'ACCEPTED',
                accepted_at = #{acceptedAt},
                version = version + 1,
                updated_by = #{actorId},
                updated_at = #{acceptedAt}
            WHERE id = #{visitId}
              AND status = 'DELIVERED'
              AND version = #{expectedVersion}
            """)
    int updateVisitAccepted(
            @Param("visitId") UUID visitId,
            @Param("expectedVersion") int expectedVersion,
            @Param("acceptedAt") Instant acceptedAt,
            @Param("actorId") UUID actorId);

    @Delete("""
            DELETE FROM inspection_order_assignment
            WHERE service_order_id = #{orderId}
            """)
    void deleteOrderAssignments(@Param("orderId") UUID orderId);

    @Insert("""
            INSERT INTO inspection_order_assignment (
                id, service_order_id, assignment_type, resource_id,
                display_name_snapshot, role_label, contact_mobile_ciphertext,
                contact_mobile_masked, certified_pilot, assigned_by, assigned_at
            )
            VALUES (
                gen_random_uuid(), #{orderId}, #{type}, #{resourceId},
                #{displayName}, #{roleLabel}, #{contactMobileCiphertext},
                #{contactMobileMasked}, #{certifiedPilot}, #{actorId}, #{assignedAt}
            )
            """)
    void insertOrderAssignment(
            @Param("orderId") UUID orderId,
            @Param("type") String type,
            @Param("resourceId") UUID resourceId,
            @Param("displayName") String displayName,
            @Param("roleLabel") String roleLabel,
            @Param("contactMobileCiphertext") byte[] contactMobileCiphertext,
            @Param("contactMobileMasked") String contactMobileMasked,
            @Param("certifiedPilot") boolean certifiedPilot,
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
                id, service_order_id, visit_id, status, tower_count,
                inspected_length_meters, metrics, issue_count, issue_group_by,
                issue_summary, published_by, published_at, version, created_at,
                updated_at
            )
            VALUES (
                #{id}, #{orderId}, #{visitId}, 'PUBLISHED', #{towerCount},
                #{inspectedLengthMeters}, CAST(#{metricsJson} AS jsonb),
                #{issueCount}, #{issueGroupBy}, CAST(#{issueSummaryJson} AS jsonb),
                #{actorId}, #{publishedAt}, #{version}, #{publishedAt}, #{publishedAt}
            )
            """)
    void insertDelivery(
            @Param("id") UUID id,
            @Param("orderId") UUID orderId,
            @Param("visitId") UUID visitId,
            @Param("towerCount") Integer towerCount,
            @Param("inspectedLengthMeters") Long inspectedLengthMeters,
            @Param("metricsJson") String metricsJson,
            @Param("issueCount") int issueCount,
            @Param("issueGroupBy") String issueGroupBy,
            @Param("issueSummaryJson") String issueSummaryJson,
            @Param("actorId") UUID actorId,
            @Param("publishedAt") Instant publishedAt,
            @Param("version") int version);

    @Insert("""
            INSERT INTO inspection_delivery_file (
                id, delivery_id, file_id, file_type, display_name,
                point_count, item_count, display_order, created_at
            )
            VALUES (
                gen_random_uuid(), #{deliveryId}, #{fileId}, #{type},
                #{displayName}, #{pointCount}, #{itemCount}, #{displayOrder}, #{createdAt}
            )
            """)
    void insertDeliveryFile(
            @Param("deliveryId") UUID deliveryId,
            @Param("fileId") UUID fileId,
            @Param("type") String type,
            @Param("displayName") String displayName,
            @Param("pointCount") Integer pointCount,
            @Param("itemCount") Integer itemCount,
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
                id, service_order_id, visit_id, delivery_id, accepted_by,
                accepted_at, note_ciphertext, idempotency_key, created_at
            )
            VALUES (
                #{id}, #{orderId}, #{visitId}, #{deliveryId}, #{acceptedBy},
                #{acceptedAt}, #{noteCiphertext}, #{idempotencyKey}, #{acceptedAt}
            )
            """)
    void insertAcceptance(
            @Param("id") UUID id,
            @Param("orderId") UUID orderId,
            @Param("visitId") UUID visitId,
            @Param("deliveryId") UUID deliveryId,
            @Param("acceptedBy") UUID acceptedBy,
            @Param("acceptedAt") Instant acceptedAt,
            @Param("noteCiphertext") byte[] noteCiphertext,
            @Param("idempotencyKey") UUID idempotencyKey);

    @Insert("""
            INSERT INTO inspection_order_progress (
                id, service_order_id, visit_id, event_code, occurred_at,
                message, operator_id, created_at
            )
            VALUES (
                #{id}, #{orderId}, #{visitId}, #{eventCode}, #{occurredAt},
                #{message}, #{actorId}, #{createdAt}
            )
            """)
    void insertOrderProgress(
            @Param("id") UUID id,
            @Param("orderId") UUID orderId,
            @Param("visitId") UUID visitId,
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
            String scopeSummary,
            Integer reportDueHours,
            Integer mediaRetentionDays,
            String changeNote,
            long totalAmountMinor,
            String currency,
            Instant publishedAt,
            Instant confirmedAt,
            UUID confirmedBy,
            UUID serviceOrderId,
            int version
    ) {
    }

    record PlanVisitRow(int visitNo, LocalDate plannedDate) {
    }

    record PlanDeliverableRow(String code, String label, String fileType) {
    }

    record PlanPaymentNodeRow(
            int nodeNo,
            String nodeType,
            Integer visitNo,
            long amountMinor
    ) {
    }

    record PlanVersionRow(
            UUID id,
            String planNo,
            int planVersion,
            String status,
            long totalAmountMinor,
            String changeNote,
            Instant publishedAt
    ) {
    }

    record ProviderRow(UUID providerId, String name, String certificationLabel, boolean qualificationReviewed) {
    }

    record CustomerPartyRow(String organizationName, String operatorName) {
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
            String paymentMode,
            String status,
            Instant createdAt,
            Instant deliveredAt,
            Instant acceptedAt,
            int version
    ) {
    }

    record ServiceVisitRow(
            UUID id,
            UUID serviceOrderId,
            int visitNo,
            LocalDate plannedDate,
            Instant plannedStartAt,
            String status,
            Instant startedAt,
            Instant deliveredAt,
            Instant acceptedAt,
            int version
    ) {
    }

    record OrderPaymentNodeRow(
            int nodeNo,
            String nodeType,
            Integer visitNo,
            UUID visitId,
            long amountMinor,
            Instant paidAt
    ) {
    }

    record OrderAssignmentRow(
            String type,
            UUID resourceId,
            String displayName,
            String roleLabel,
            String contactMobileMasked,
            boolean certifiedPilot
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
            UUID visitId,
            String status,
            Integer towerCount,
            Long inspectedLengthMeters,
            int issueCount,
            String issueGroupBy,
            Instant publishedAt,
            Instant acceptedAt,
            int version
    ) {
    }

    record DeliveryIssueRow(String groupCode, String groupName, int count) {
    }

    record DeliveryMetricRow(String code, String label, BigDecimal metricValue, String unit) {
    }

    record DeliveryFileRow(
            UUID fileId,
            String type,
            String displayName,
            Integer pointCount,
            Integer itemCount,
            String contentType,
            long sizeBytes,
            String status,
            String bucket,
            String objectKey
    ) {
    }

    record DeliveryFileObjectRow(
            UUID id,
            UUID visitId,
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
            UUID visitId,
            UUID deliveryId,
            UUID acceptedBy,
            String acceptedByName,
            Instant acceptedAt,
            byte[] noteCiphertext,
            int visitVersion,
            String orderStatus
    ) {
    }

    record IdempotencyRow(String requestDigest, UUID resourceId) {
    }
}
