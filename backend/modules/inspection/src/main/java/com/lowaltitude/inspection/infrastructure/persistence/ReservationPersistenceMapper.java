package com.lowaltitude.inspection.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ReservationPersistenceMapper {
    @Select("SELECT id service_id, provider_id, title, cover_url, city_code FROM inspection_service WHERE id=#{id} AND city_code=#{cityCode} AND status='ACTIVE'")
    Optional<ServiceRow> findActiveService(@Param("id") UUID id, @Param("cityCode") String cityCode);

    @Select("SELECT request_hash request_digest, resource_id reservation_id FROM inspection_idempotency_record WHERE actor_id=#{actorId} AND operation_code='inspection.reservation.create' AND idempotency_key=#{key}")
    Optional<IdempotencyRow> findIdempotency(@Param("actorId") UUID actorId, @Param("key") UUID key);

    @Select("SELECT r.id,r.reservation_no,r.customer_id,r.provider_id,r.service_id,r.service_title_snapshot title,r.service_cover_url_snapshot cover_url,r.city_code,r.address_ciphertext,ST_X(r.location::geometry) longitude,ST_Y(r.location::geometry) latitude,r.line_length_meters,r.preferred_service_date,r.note_ciphertext,r.contact_name_ciphertext,r.contact_mobile_ciphertext,r.agreement_version,r.submitted_at,r.version FROM inspection_reservation r WHERE r.id=#{id}")
    Optional<ReservationRow> findReservation(@Param("id") UUID id);

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

    @Select("SELECT id service_order_id, customer_id, provider_id, status order_status FROM inspection_service_order WHERE id=#{serviceOrderId}")
    Optional<DeliveryFileOwnerRow> findDeliveryFileOwner(@Param("serviceOrderId") UUID serviceOrderId);

    @Select("SELECT count(*) FROM inspection_file_object WHERE owner_id=#{serviceOrderId} AND purpose='DELIVERY_ARTIFACT' AND file_status <> 'DELETED'")
    int countDeliveryFiles(@Param("serviceOrderId") UUID serviceOrderId);

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
                preferred_service_date, note_ciphertext,
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
                #{lineLengthMeters}, #{preferredServiceDate},
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
    record DeliveryFileOwnerRow(UUID serviceOrderId, UUID customerId, UUID providerId, String orderStatus) { }
    record ReservationFlowStateRow(String status, UUID currentPlanId, int version) { }
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
    record ReservationRow(UUID id, String reservationNo, UUID customerId, UUID providerId, UUID serviceId, String title, String coverUrl,
                          String cityCode, byte[] addressCiphertext, Double longitude, Double latitude, Long lineLengthMeters,
                          LocalDate preferredServiceDate, byte[] noteCiphertext, byte[] contactNameCiphertext, byte[] contactMobileCiphertext,
                          String agreementVersion, Instant submittedAt, int version) { }
}
