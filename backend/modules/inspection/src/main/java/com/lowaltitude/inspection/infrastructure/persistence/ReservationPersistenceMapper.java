package com.lowaltitude.inspection.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import com.lowaltitude.inspection.application.reservation.InspectionDemandSource;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ReservationPersistenceMapper {
    @Select("""
            SELECT b.demand_id, '巡检需求' AS title, b.quote_id, b.quote_version, b.provider_id, b.provider_name,
                   b.amount_minor, b.summary, d.city_code, d.address, d.scope_text AS scope
            FROM demand_inspection_booking b JOIN demand d ON d.id = b.demand_id
            WHERE b.reservation_id = #{reservationId}
            """)
    Optional<InspectionDemandSource> findDemandSource(
            @Param("reservationId") UUID reservationId);

    @Select("""
            SELECT service.id service_id,
                   service.provider_id,
                   service.title,
                   service.cover_url,
                   coverage.city_code
            FROM inspection_service service
            JOIN inspection_service_city coverage
              ON coverage.service_id = service.id
             AND coverage.city_code = #{cityCode}
             AND coverage.status = 'ACTIVE'
            WHERE service.id = #{id}
              AND service.status = 'ACTIVE'
              AND NOT EXISTS (
                  SELECT 1
                  FROM identity_organization suspended
                  WHERE suspended.id = service.provider_id
                    AND suspended.listing_suspended
              )
            """)
    Optional<ServiceRow> findActiveService(@Param("id") UUID id, @Param("cityCode") String cityCode);

    @Select("SELECT request_hash request_digest, resource_id reservation_id FROM inspection_idempotency_record WHERE actor_id=#{actorId} AND operation_code='inspection.reservation.create' AND idempotency_key=#{key}")
    Optional<IdempotencyRow> findIdempotency(@Param("actorId") UUID actorId, @Param("key") UUID key);

    @Select("""
            SELECT request_hash request_digest, resource_id reservation_id
            FROM inspection_idempotency_record
            WHERE actor_id = #{actorId}
              AND operation_code = 'inspection.reservation.cancel'
              AND idempotency_key = #{key}
            """)
    Optional<IdempotencyRow> findCancellationIdempotency(
            @Param("actorId") UUID actorId,
            @Param("key") UUID key);

    @Select("SELECT r.id,r.reservation_no,r.customer_id,r.provider_id,r.service_id,r.service_title_snapshot title,r.service_cover_url_snapshot cover_url,r.city_code,r.address_ciphertext,ST_X(r.location::geometry) longitude,ST_Y(r.location::geometry) latitude,r.line_length_meters,r.preferred_service_date,r.preferred_time_slot_code,r.note_ciphertext,r.contact_name_ciphertext,r.contact_mobile_ciphertext,r.agreement_version,r.submitted_at,r.version FROM inspection_reservation r WHERE r.id=#{id}")
    Optional<ReservationRow> findReservation(@Param("id") UUID id);

    @Select("""
            SELECT service.id
            FROM inspection_service service
            JOIN inspection_service_city coverage
              ON coverage.service_id = service.id
             AND coverage.city_code = #{cityCode}
             AND coverage.status = 'ACTIVE'
            WHERE service.id = #{serviceId}
              AND service.status = 'ACTIVE'
              AND NOT EXISTS (
                  SELECT 1
                  FROM identity_organization suspended
                  WHERE suspended.id = service.provider_id
                    AND suspended.listing_suspended
              )
            FOR UPDATE
            """)
    UUID lockActiveService(
            @Param("serviceId") UUID serviceId,
            @Param("cityCode") String cityCode
    );

    @Select("""
            SELECT preferred_service_date service_date,
                   preferred_time_slot_code slot_code,
                   count(*) booked_count
            FROM inspection_reservation
            WHERE service_id = #{serviceId}
              AND city_code = #{cityCode}
              AND preferred_service_date BETWEEN #{startDate} AND #{endDate}
              AND status <> 'CANCELLED'
            GROUP BY preferred_service_date, preferred_time_slot_code
            """)
    List<TimeSlotCountRow> findBookedTimeSlotCounts(
            @Param("serviceId") UUID serviceId,
            @Param("cityCode") String cityCode,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    @Select("""
            SELECT r.status,
                   (
                       SELECT p.id
                       FROM inspection_service_plan p
                       WHERE p.reservation_id = r.id
                         AND p.status IN ('PUBLISHED', 'CONFIRMED')
                       ORDER BY p.plan_version DESC
                       LIMIT 1
                   ) current_plan_id,
                   r.version
            FROM inspection_reservation r
            WHERE r.id = #{reservationId}
            """)
    Optional<ReservationFlowStateRow> findReservationFlowState(
            @Param("reservationId") UUID reservationId);

    /**
     * 巡检订单的列表投影（客户的“我的订单”和 S3-2 起服务商工作台的“巡检预约”共用）：当前方案、服务单、
     * 各次巡检的计数和待验收的那一次，以及按服务端状态推导的阶段。只读地址密文，不读联系人。
     */
    String RESERVATION_SUMMARY_SELECT = """

            SELECT r.id reservation_id,
                   r.reservation_no,
                   r.service_id,
                   r.service_title_snapshot service_title,
                   r.service_cover_url_snapshot service_cover_url,
                   r.city_code,
                   r.address_ciphertext location_ciphertext,
                   r.preferred_service_date,
                   r.submitted_at,
                   lifecycle.stage,
                   r.status reservation_status,
                   p.id current_plan_id,
                   o.id service_order_id,
                   o.status service_order_status,
                   p.total_amount_minor quote_amount_minor,
                   COALESCE(v.visit_count, 0) visit_count,
                   COALESCE(v.accepted_visit_count, 0) accepted_visit_count,
                   v.next_visit_date,
                   pending.id pending_acceptance_visit_id,
                   pending.visit_no pending_acceptance_visit_no,
                   due.node_no payment_due_node_no,
                   due.node_type payment_due_node_type,
                   due.visit_no payment_due_visit_no,
                   due.amount_minor payment_due_amount_minor,
                   provider.id provider_id,
                   provider.organization_name provider_name,
                   provider.qualification_reviewed_at IS NOT NULL provider_qualification_reviewed
            FROM inspection_reservation r
            -- S4-4：订单卡头行的服务商名和“资质已审核”；历史订单不按机构现在的状态隐藏。
            LEFT JOIN identity_organization provider
              ON provider.id = r.provider_id
             AND provider.organization_type = 'PROVIDER'
            LEFT JOIN LATERAL (
                SELECT current_plan.id,
                       current_plan.status,
                       current_plan.total_amount_minor,
                       current_plan.updated_at
                FROM inspection_service_plan current_plan
                WHERE current_plan.reservation_id = r.id
                  AND current_plan.status IN ('PUBLISHED', 'CONFIRMED')
                ORDER BY current_plan.plan_version DESC
                LIMIT 1
            ) p ON true
            LEFT JOIN inspection_service_order o ON o.reservation_id = r.id
            LEFT JOIN LATERAL (
                SELECT count(*) visit_count,
                       count(*) FILTER (WHERE visit.status = 'ACCEPTED') accepted_visit_count,
                       min(COALESCE(
                           (visit.planned_start_at AT TIME ZONE 'Asia/Shanghai')::date,
                           visit.planned_date
                       )) FILTER (
                           WHERE visit.status IN ('PENDING_SCHEDULE', 'SCHEDULED')
                       ) next_visit_date,
                       max(visit.updated_at) last_updated_at
                FROM inspection_service_visit visit
                WHERE visit.service_order_id = o.id
            ) v ON true
            LEFT JOIN LATERAL (
                SELECT visit.id, visit.visit_no
                FROM inspection_service_visit visit
                WHERE visit.service_order_id = o.id
                  AND visit.status = 'DELIVERED'
                ORDER BY visit.visit_no
                LIMIT 1
            ) pending ON true
            LEFT JOIN LATERAL (
                -- S4-1：现在该付的第一个节点（首付在确认后，按次服务费在这一次验收后；已取消的不再收）。
                SELECT node.node_no, node.node_type, visit.visit_no, node.amount_minor
                FROM inspection_order_payment_node node
                LEFT JOIN inspection_service_visit visit ON visit.id = node.visit_id
                WHERE node.service_order_id = o.id
                  AND o.payment_mode = 'NODES'
                  AND o.status <> 'CANCELLED'
                  AND node.paid_at IS NULL
                  AND (node.node_type = 'DOWN_PAYMENT' OR visit.status = 'ACCEPTED')
                ORDER BY node.node_no
                LIMIT 1
            ) due ON true
            CROSS JOIN LATERAL (
                -- 全部验收但还有没付的按次服务费时算进行中，付清后才算已完成（S4-1）。
                SELECT CASE
                           WHEN r.status = 'CANCELLED' OR o.status = 'CANCELLED' THEN 'CANCELLED'
                           WHEN o.status = 'ACCEPTED' AND due.node_no IS NULL THEN 'COMPLETED'
                           WHEN pending.id IS NOT NULL THEN 'AWAITING_ACCEPTANCE'
                           WHEN o.id IS NOT NULL THEN 'IN_PROGRESS'
                           WHEN p.status = 'PUBLISHED' THEN 'AWAITING_CONFIRMATION'
                           ELSE 'AWAITING_PLAN'
                       END stage,
                       GREATEST(r.updated_at, p.updated_at, o.updated_at, v.last_updated_at)
                           lifecycle_updated_at
            ) lifecycle
""";

    @Select(RESERVATION_SUMMARY_SELECT + """
            WHERE r.customer_id = #{customerId}
              AND (#{stage} = 'ALL' OR lifecycle.stage = #{stage})
            ORDER BY lifecycle_updated_at DESC, r.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<CustomerReservationSummaryRow> findCustomerReservations(
            @Param("customerId") UUID customerId,
            @Param("stage") String stage,
            @Param("limit") int limit,
            @Param("offset") int offset);

    /** 各阶段的计数用的投影（和上面的阶段推导一致），后面接 WHERE 限定客户或服务商。 */
    String RESERVATION_STAGE_PROJECTION = """

            WITH order_projection AS (
                SELECT r.id,
                       CASE
                           WHEN r.status = 'CANCELLED' OR o.status = 'CANCELLED' THEN 'CANCELLED'
                           WHEN o.status = 'ACCEPTED' AND NOT EXISTS (
                               SELECT 1
                               FROM inspection_order_payment_node node
                               WHERE node.service_order_id = o.id
                                 AND o.payment_mode = 'NODES'
                                 AND node.paid_at IS NULL
                           ) THEN 'COMPLETED'
                           WHEN EXISTS (
                               SELECT 1
                               FROM inspection_service_visit visit
                               WHERE visit.service_order_id = o.id
                                 AND visit.status = 'DELIVERED'
                           ) THEN 'AWAITING_ACCEPTANCE'
                           WHEN o.id IS NOT NULL THEN 'IN_PROGRESS'
                           WHEN p.status = 'PUBLISHED' THEN 'AWAITING_CONFIRMATION'
                           ELSE 'AWAITING_PLAN'
                       END stage
                FROM inspection_reservation r
                LEFT JOIN LATERAL (
                    SELECT current_plan.id, current_plan.status
                    FROM inspection_service_plan current_plan
                    WHERE current_plan.reservation_id = r.id
                      AND current_plan.status IN ('PUBLISHED', 'CONFIRMED')
                    ORDER BY current_plan.plan_version DESC
                    LIMIT 1
                ) p ON true
                LEFT JOIN inspection_service_order o ON o.reservation_id = r.id
""";

    String RESERVATION_STAGE_COUNTS = """
            )
            SELECT count(*) all_count,
                   count(*) FILTER (WHERE stage = 'AWAITING_PLAN') awaiting_plan,
                   count(*) FILTER (WHERE stage = 'AWAITING_CONFIRMATION') awaiting_confirmation,
                   count(*) FILTER (WHERE stage = 'IN_PROGRESS') in_progress,
                   count(*) FILTER (WHERE stage = 'AWAITING_ACCEPTANCE') awaiting_acceptance,
                   count(*) FILTER (WHERE stage = 'COMPLETED') completed
            FROM order_projection
""";

    @Select(RESERVATION_STAGE_PROJECTION + """
                WHERE r.customer_id = #{customerId}
            """ + RESERVATION_STAGE_COUNTS)
    CustomerReservationCountsRow countCustomerReservations(
            @Param("customerId") UUID customerId);

    /** S3-2 服务商工作台：分给本机构（provider_id）的巡检预约，阶段推导和客户订单相同。 */
    @Select(RESERVATION_SUMMARY_SELECT + """
            WHERE r.provider_id = #{providerId}
              AND (#{stage} = 'ALL' OR lifecycle.stage = #{stage})
            ORDER BY lifecycle_updated_at DESC, r.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<CustomerReservationSummaryRow> findProviderReservations(
            @Param("providerId") UUID providerId,
            @Param("stage") String stage,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select(RESERVATION_SUMMARY_SELECT + """
            WHERE r.provider_id = #{providerId}
              AND r.id = #{reservationId}
            """)
    Optional<CustomerReservationSummaryRow> findProviderReservation(
            @Param("providerId") UUID providerId,
            @Param("reservationId") UUID reservationId);

    @Select(RESERVATION_STAGE_PROJECTION + """
                WHERE r.provider_id = #{providerId}
            """ + RESERVATION_STAGE_COUNTS)
    CustomerReservationCountsRow countProviderReservations(
            @Param("providerId") UUID providerId);

    @Select("SELECT item_code code, item_name_snapshot label FROM inspection_reservation_item WHERE reservation_id=#{id} ORDER BY display_order, id")
    List<ItemRow> findItems(@Param("id") UUID id);

    @Select("""
            SELECT item_code code, item_label label
            FROM inspection_service_booking_item
            WHERE service_id = #{serviceId}
              AND status = 'ACTIVE'
            ORDER BY display_order, id
            """)
    List<ItemRow> findServiceBookingItems(@Param("serviceId") UUID serviceId);

    @Select("""
            SELECT f.id, f.original_name, f.content_type, f.size_bytes,
                   f.file_status status, f.scan_failure_reason failure_reason
            FROM inspection_reservation_attachment a
            JOIN inspection_file_object f ON f.id = a.file_id
            WHERE a.reservation_id = #{reservationId}
              AND f.file_status <> 'DELETED'
            ORDER BY a.display_order, a.created_at, f.id
            """)
    List<ReservationFileRow> findReservationFiles(@Param("reservationId") UUID reservationId);

    @Select("""
            SELECT id reservation_id, customer_id, provider_id
            FROM inspection_reservation
            WHERE id = #{reservationId}
            """)
    Optional<ReservationOwnerRow> findReservationOwner(
            @Param("reservationId") UUID reservationId
    );

    @Select("""
            SELECT count(*)
            FROM inspection_file_object
            WHERE owner_id = #{reservationId}
              AND purpose = 'RESERVATION_ATTACHMENT'
              AND file_status <> 'DELETED'
            """)
    int countReservationFiles(@Param("reservationId") UUID reservationId);

    @Select("""
            SELECT v.id visit_id, o.id service_order_id, o.customer_id, o.provider_id,
                   v.status visit_status
            FROM inspection_service_visit v
            JOIN inspection_service_order o ON o.id = v.service_order_id
            WHERE v.id = #{visitId}
            """)
    Optional<DeliveryFileOwnerRow> findDeliveryFileOwner(@Param("visitId") UUID visitId);

    @Select("SELECT count(*) FROM inspection_file_object WHERE owner_id=#{visitId} AND purpose='DELIVERY_ARTIFACT' AND file_status <> 'DELETED'")
    int countDeliveryFiles(@Param("visitId") UUID visitId);

    @Select("""
            SELECT request_hash request_digest, resource_id file_id
            FROM inspection_idempotency_record
            WHERE actor_id = #{actorId}
              AND operation_code = #{operation}
              AND idempotency_key = #{key}
            """)
    Optional<FileIdempotencyRow> findFileIdempotency(
            @Param("actorId") UUID actorId,
            @Param("operation") String operation,
            @Param("key") UUID key
    );

    @Insert("""
            INSERT INTO inspection_reservation (
                id, reservation_no, customer_id, provider_id, service_id,
                service_title_snapshot, service_cover_url_snapshot, city_code,
                address_ciphertext, location, line_length_meters,
                preferred_service_date, preferred_time_slot_code, note_ciphertext,
                contact_name_ciphertext, contact_mobile_ciphertext,
                agreement_version, created_by, updated_by, submitted_at
            )
            VALUES (
                #{id}, #{reservationNo}, #{customerId}, #{providerId},
                #{serviceId}, #{title}, #{coverUrl}, #{cityCode},
                #{addressCiphertext},
                CASE
                    WHEN CAST(#{longitude} AS double precision) IS NULL
                      OR CAST(#{latitude} AS double precision) IS NULL
                    THEN NULL
                    ELSE ST_SetSRID(
                        ST_MakePoint(
                            CAST(#{longitude} AS double precision),
                            CAST(#{latitude} AS double precision)
                        ),
                        4326
                    )::geography
                END,
                #{lineLengthMeters}, #{preferredServiceDate}, #{preferredTimeSlotCode},
                #{noteCiphertext}, #{contactNameCiphertext},
                #{contactMobileCiphertext}, #{agreementVersion},
                #{customerId}, #{customerId}, #{submittedAt}
            )
            """)
    void insertReservation(ReservationRow row);

    @Insert("INSERT INTO inspection_reservation_item (id,reservation_id,item_code,item_name_snapshot,display_order) VALUES (#{id},#{reservationId},#{code},#{label},#{displayOrder})")
    void insertItem(@Param("id") UUID id, @Param("reservationId") UUID reservationId, @Param("code") String code, @Param("label") String label, @Param("displayOrder") int displayOrder);

    @Insert("""
            INSERT INTO inspection_idempotency_record (
                id, actor_id, operation_code, idempotency_key, request_hash,
                resource_type, resource_id, response_status, response_body, expires_at
            )
            VALUES (
                gen_random_uuid(), #{actorId}, 'inspection.reservation.create', #{key}, #{digest},
                'RESERVATION', #{reservationId}, 201, '{}'::jsonb, now() + interval '24 hours'
            )
            ON CONFLICT (actor_id, operation_code, idempotency_key) DO NOTHING
            """)
    int insertIdempotency(
            @Param("actorId") UUID actorId,
            @Param("key") UUID key,
            @Param("digest") String digest,
            @Param("reservationId") UUID reservationId
    );

    @Insert("""
            INSERT INTO inspection_idempotency_record (
                id, actor_id, operation_code, idempotency_key, request_hash,
                resource_type, resource_id, response_status, response_body, expires_at
            ) VALUES (
                gen_random_uuid(), #{actorId}, 'inspection.reservation.cancel', #{key}, #{digest},
                'RESERVATION', #{reservationId}, 200, '{}'::jsonb, now() + interval '24 hours'
            )
            ON CONFLICT (actor_id, operation_code, idempotency_key) DO NOTHING
            """)
    int insertCancellationIdempotency(
            @Param("actorId") UUID actorId,
            @Param("key") UUID key,
            @Param("digest") String digest,
            @Param("reservationId") UUID reservationId);

    @Update("""
            UPDATE inspection_service_plan
            SET status = 'WITHDRAWN', version = version + 1,
                updated_by = #{actorId}, updated_at = #{cancelledAt}
            WHERE id = #{planId}
              AND reservation_id = #{reservationId}
              AND status = 'PUBLISHED'
            """)
    int withdrawPublishedPlanForCancellation(
            @Param("reservationId") UUID reservationId,
            @Param("planId") UUID planId,
            @Param("actorId") UUID actorId,
            @Param("cancelledAt") Instant cancelledAt);

    @Update("""
            UPDATE inspection_reservation r
            SET status = 'CANCELLED', cancelled_at = #{cancelledAt}, version = version + 1,
                updated_by = #{actorId}, updated_at = #{cancelledAt}
            WHERE r.id = #{reservationId}
              AND r.customer_id = #{customerId}
              AND r.version = #{expectedVersion}
              AND r.status IN ('SUBMITTED', 'PLAN_PUBLISHED')
              AND NOT EXISTS (
                  SELECT 1 FROM inspection_service_plan p
                  WHERE p.reservation_id = r.id AND p.status = 'CONFIRMED'
              )
              AND NOT EXISTS (
                  SELECT 1 FROM inspection_service_order o
                  WHERE o.reservation_id = r.id
              )
            """)
    int cancelReservation(
            @Param("reservationId") UUID reservationId,
            @Param("customerId") UUID customerId,
            @Param("expectedVersion") int expectedVersion,
            @Param("actorId") UUID actorId,
            @Param("cancelledAt") Instant cancelledAt);

    @Insert("""
            INSERT INTO inspection_idempotency_record (
                id, actor_id, operation_code, idempotency_key, request_hash,
                resource_type, resource_id, response_status, response_body, expires_at
            )
            VALUES (
                gen_random_uuid(), #{actorId}, #{operation}, #{key}, #{digest},
                'FILE_OBJECT', #{fileId}, #{responseStatus}, '{}'::jsonb,
                now() + interval '24 hours'
            )
            ON CONFLICT (actor_id, operation_code, idempotency_key) DO NOTHING
            """)
    int insertFileIdempotency(
            @Param("actorId") UUID actorId,
            @Param("operation") String operation,
            @Param("key") UUID key,
            @Param("digest") String digest,
            @Param("fileId") UUID fileId,
            @Param("responseStatus") int responseStatus
    );

    @Insert("""
            INSERT INTO inspection_file_object (
                id, purpose, owner_id, provider_id, bucket_name, object_key,
                original_name, content_type, extension, size_bytes, sha256,
                object_etag, file_status, scan_failure_reason, uploaded_by,
                created_at, updated_at, version
            )
            VALUES (
                #{id}, #{purpose}, #{ownerId}, #{providerId}, #{bucket},
                #{objectKey}, #{originalName}, #{contentType}, #{extension},
                #{sizeBytes}, #{sha256}, #{etag}, #{status}, #{failureReason},
                #{uploadedBy}, #{createdAt}, #{createdAt}, #{version}
            )
            """)
    void insertFileObject(FileRow row);

    @Select("""
            SELECT id, purpose, owner_id, provider_id, bucket_name bucket,
                   object_key, original_name, content_type, extension,
                   size_bytes, sha256, object_etag etag, file_status status,
                   scan_failure_reason failure_reason, uploaded_by,
                   created_at, version
            FROM inspection_file_object
            WHERE id = #{fileId}
            """)
    Optional<FileRow> findFile(@Param("fileId") UUID fileId);

    @Update("""
            UPDATE inspection_file_object
            SET file_status = #{next},
                object_etag = COALESCE(#{etag}, object_etag),
                scan_failure_reason = #{failureReason},
                uploaded_at = CASE
                    WHEN #{next} = 'UPLOADED' THEN now()
                    ELSE uploaded_at
                END,
                scanned_at = CASE
                    WHEN #{next} IN ('READY', 'REJECTED') THEN now()
                    ELSE scanned_at
                END,
                version = version + 1,
                updated_at = now()
            WHERE id = #{fileId}
              AND file_status = #{expected}
            """)
    int updateFileStatus(
            @Param("fileId") UUID fileId,
            @Param("expected") String expected,
            @Param("next") String next,
            @Param("etag") String etag,
            @Param("failureReason") String failureReason
    );

    @Insert("""
            INSERT INTO inspection_reservation_attachment (
                reservation_id, file_id, display_order
            )
            VALUES (
                #{reservationId},
                #{fileId},
                (
                    SELECT count(*)
                    FROM inspection_reservation_attachment
                    WHERE reservation_id = #{reservationId}
                )
            )
            ON CONFLICT (reservation_id, file_id) DO NOTHING
            """)
    int linkReservationAttachment(
            @Param("reservationId") UUID reservationId,
            @Param("fileId") UUID fileId
    );

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

    record ServiceRow(UUID serviceId, UUID providerId, String title, String coverUrl, String cityCode) { }
    record IdempotencyRow(String requestDigest, UUID reservationId) { }
    record ItemRow(String code, String label) { }
    record ReservationFileRow(
            UUID id,
            String originalName,
            String contentType,
            long sizeBytes,
            String status,
            String failureReason
    ) { }
    record ReservationOwnerRow(UUID reservationId, UUID customerId, UUID providerId) { }
    record DeliveryFileOwnerRow(UUID visitId, UUID serviceOrderId, UUID customerId, UUID providerId, String visitStatus) { }
    record ReservationFlowStateRow(String status, UUID currentPlanId, int version) { }
    record CustomerReservationSummaryRow(
            UUID reservationId,
            String reservationNo,
            UUID serviceId,
            String serviceTitle,
            String serviceCoverUrl,
            String cityCode,
            byte[] locationCiphertext,
            LocalDate preferredServiceDate,
            Instant submittedAt,
            String stage,
            String reservationStatus,
            UUID currentPlanId,
            UUID serviceOrderId,
            String serviceOrderStatus,
            Long quoteAmountMinor,
            int visitCount,
            int acceptedVisitCount,
            LocalDate nextVisitDate,
            UUID pendingAcceptanceVisitId,
            Integer pendingAcceptanceVisitNo,
            Integer paymentDueNodeNo,
            String paymentDueNodeType,
            Integer paymentDueVisitNo,
            Long paymentDueAmountMinor,
            UUID providerId,
            String providerName,
            boolean providerQualificationReviewed
    ) { }
    record CustomerReservationCountsRow(
            long allCount,
            long awaitingPlan,
            long awaitingConfirmation,
            long inProgress,
            long awaitingAcceptance,
            long completed
    ) { }
    record FileIdempotencyRow(String requestDigest, UUID fileId) { }
    record FileRow(
            UUID id,
            String purpose,
            UUID ownerId,
            UUID providerId,
            String bucket,
            String objectKey,
            String originalName,
            String contentType,
            String extension,
            long sizeBytes,
            String sha256,
            String etag,
            String status,
            String failureReason,
            UUID uploadedBy,
            Instant createdAt,
            int version
    ) { }
    record TimeSlotCountRow(LocalDate serviceDate, String slotCode, int bookedCount) { }
    record ReservationRow(UUID id, String reservationNo, UUID customerId, UUID providerId, UUID serviceId, String title, String coverUrl,
                          String cityCode, byte[] addressCiphertext, Double longitude, Double latitude, Long lineLengthMeters,
                          LocalDate preferredServiceDate, String preferredTimeSlotCode, byte[] noteCiphertext, byte[] contactNameCiphertext, byte[] contactMobileCiphertext,
                          String agreementVersion, Instant submittedAt, int version) { }
}
