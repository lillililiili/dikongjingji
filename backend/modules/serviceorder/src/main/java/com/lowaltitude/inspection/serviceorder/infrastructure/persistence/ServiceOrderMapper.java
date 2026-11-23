package com.lowaltitude.inspection.serviceorder.infrastructure.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 服务订单、方案、付款尝试、退款、交付、文件、下载令牌和订单记录的读写（S4-3）。全部用绑定参数；
 * 客户的读写以账号为条件，服务商的读写以服务商机构为条件。状态变化都是带“从哪个状态”的条件更新，版本号加 1。
 * “当前时间”由调用方传入。加锁顺序：交易订单（交易模块）→ 服务订单。
 */
public interface ServiceOrderMapper {

    // ---- 订单 ----

    @Select("SELECT 1 FROM service_order WHERE order_no = #{orderNo}")
    Integer orderNoTaken(@Param("orderNo") String orderNo);

    @Select("SELECT id FROM service_order WHERE demand_id = #{demandId}")
    UUID findIdByDemand(@Param("demandId") UUID demandId);

    @Insert("""
            INSERT INTO service_order (
                id, order_no, demand_id, quote_id, quote_version, account_id, customer_kind, customer_organization_id,
                customer_organization_name, contact_name, provider_id, provider_name, type, title, city_code, city_name,
                address, scope_text, due_date, amount_minor, quote_method, quote_schedule, quote_duration_days,
                quote_deliverables, quote_team, quote_night_work, quote_report_hours, quote_archive_days,
                quote_first_visit_date, status, created_at, updated_at
            )
            VALUES (
                #{id}, #{orderNo}, #{demandId}, #{quoteId}, #{quoteVersion}, #{accountId}, #{customerKind},
                #{customerOrganizationId}, #{customerOrganizationName}, #{contactName}, #{providerId}, #{providerName},
                #{type}, #{title}, #{cityCode}, #{cityName}, #{address}, #{scope}, #{dueDate}, #{amountMinor},
                #{method}, #{schedule}, #{durationDays}, #{deliverables}, #{team}, #{nightWork}, #{reportHours},
                #{archiveDays}, #{firstVisitDate}, 'AWAITING_PLAN', #{at}, #{at}
            )
            """)
    int insertOrder(
            @Param("id") UUID id,
            @Param("orderNo") String orderNo,
            @Param("demandId") UUID demandId,
            @Param("quoteId") UUID quoteId,
            @Param("quoteVersion") int quoteVersion,
            @Param("accountId") UUID accountId,
            @Param("customerKind") String customerKind,
            @Param("customerOrganizationId") UUID customerOrganizationId,
            @Param("customerOrganizationName") String customerOrganizationName,
            @Param("contactName") String contactName,
            @Param("providerId") UUID providerId,
            @Param("providerName") String providerName,
            @Param("type") String type,
            @Param("title") String title,
            @Param("cityCode") String cityCode,
            @Param("cityName") String cityName,
            @Param("address") String address,
            @Param("scope") String scope,
            @Param("dueDate") LocalDate dueDate,
            @Param("amountMinor") long amountMinor,
            @Param("method") String method,
            @Param("schedule") String schedule,
            @Param("durationDays") int durationDays,
            @Param("deliverables") String deliverables,
            @Param("team") String team,
            @Param("nightWork") String nightWork,
            @Param("reportHours") Integer reportHours,
            @Param("archiveDays") Integer archiveDays,
            @Param("firstVisitDate") LocalDate firstVisitDate,
            @Param("at") OffsetDateTime at
    );

    String ORDER_COLUMNS = """
            SELECT o.id,
                   o.order_no,
                   o.demand_id,
                   o.quote_id,
                   o.quote_version,
                   o.account_id,
                   o.customer_kind,
                   o.customer_organization_name,
                   o.contact_name,
                   o.provider_id,
                   o.provider_name,
                   o.type,
                   o.title,
                   o.city_code,
                   o.city_name,
                   o.address,
                   o.scope_text,
                   o.due_date,
                   o.amount_minor,
                   o.quote_method,
                   o.quote_schedule,
                   o.quote_duration_days,
                   o.quote_deliverables,
                   o.quote_team,
                   o.quote_night_work,
                   o.quote_report_hours,
                   o.quote_archive_days,
                   o.quote_first_visit_date,
                   o.status,
                   o.plan_version,
                   o.paid_at,
                   o.started_at,
                   o.completed_at,
                   o.closed_at,
                   o.closed_by,
                   o.close_reason,
                   (SELECT max(d.delivered_at) FROM service_order_delivery d WHERE d.order_id = o.id) AS latest_delivery_at,
                   o.created_at,
                   o.updated_at,
                   o.version
            FROM service_order o
            """;

    @Select(ORDER_COLUMNS + "WHERE o.id = #{orderId} AND o.account_id = #{accountId}")
    OrderRow findOrder(@Param("accountId") UUID accountId, @Param("orderId") UUID orderId);

    @Select(ORDER_COLUMNS + "WHERE o.id = #{orderId} AND o.account_id = #{accountId} FOR UPDATE OF o")
    OrderRow lockOrder(@Param("accountId") UUID accountId, @Param("orderId") UUID orderId);

    @Select(ORDER_COLUMNS + "WHERE o.id = #{orderId} AND o.provider_id = #{providerId}")
    OrderRow findProviderOrder(@Param("providerId") UUID providerId, @Param("orderId") UUID orderId);

    @Select(ORDER_COLUMNS + "WHERE o.id = #{orderId} AND o.provider_id = #{providerId} FOR UPDATE OF o")
    OrderRow lockProviderOrder(@Param("providerId") UUID providerId, @Param("orderId") UUID orderId);

    /** 交易回调：按付款尝试找到的订单。 */
    @Select(ORDER_COLUMNS + "WHERE o.id = #{orderId} FOR UPDATE OF o")
    OrderRow lockOrderById(@Param("orderId") UUID orderId);

    @Select("""
            <script>
            SELECT count(*) FROM service_order o
            WHERE o.account_id = #{accountId}
            <if test="status != null">AND o.status = #{status}</if>
            </script>
            """)
    long countOrders(@Param("accountId") UUID accountId, @Param("status") String status);

    @Select("<script>" + ORDER_COLUMNS + """
            WHERE o.account_id = #{accountId}
            <if test="status != null">AND o.status = #{status}</if>
            ORDER BY o.updated_at DESC, o.id
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<OrderRow> findOrderPage(
            @Param("accountId") UUID accountId,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select("""
            <script>
            SELECT count(*) FROM service_order o
            WHERE o.provider_id = #{providerId}
            <if test="status != null">AND o.status = #{status}</if>
            </script>
            """)
    long countProviderOrders(@Param("providerId") UUID providerId, @Param("status") String status);

    @Select("<script>" + ORDER_COLUMNS + """
            WHERE o.provider_id = #{providerId}
            <if test="status != null">AND o.status = #{status}</if>
            ORDER BY o.updated_at DESC, o.id
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<OrderRow> findProviderOrderPage(
            @Param("providerId") UUID providerId,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /** 出方案或改方案：待出方案、待确认 → 待确认，指向最新一版。 */
    @Update("""
            UPDATE service_order
            SET status = 'PLAN_PENDING', plan_version = #{planVersion}, version = version + 1, updated_at = #{at}
            WHERE id = #{orderId} AND status IN ('AWAITING_PLAN', 'PLAN_PENDING')
            """)
    int setPlan(@Param("orderId") UUID orderId, @Param("planVersion") int planVersion, @Param("at") OffsetDateTime at);

    /**
     * 一步状态变化：fromStatus → toStatus；进入 AWAITING_START 时记付款时间，进入 IN_SERVICE 时记开工时间，
     * 进入 COMPLETED 时记完成时间。
     */
    @Update("""
            <script>
            UPDATE service_order
            SET status = #{toStatus},
                <if test="toStatus == 'AWAITING_START'">paid_at = #{at},</if>
                <if test="toStatus == 'IN_SERVICE'">started_at = #{at},</if>
                <if test="toStatus == 'COMPLETED'">completed_at = #{at},</if>
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{orderId} AND status = #{fromStatus}
            </script>
            """)
    int moveOrder(
            @Param("orderId") UUID orderId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("at") OffsetDateTime at
    );

    /** 取消（开工前）或协商终止（开工后）：只在调用方核对过的那个状态上改。 */
    @Update("""
            UPDATE service_order
            SET status = #{toStatus}, closed_at = #{at}, closed_by = #{closedBy}, close_reason = #{reason},
                version = version + 1, updated_at = #{at}
            WHERE id = #{orderId} AND status = #{fromStatus}
              AND status IN ('AWAITING_PLAN', 'PLAN_PENDING', 'AWAITING_PAYMENT', 'AWAITING_START', 'IN_SERVICE',
                             'DELIVERED', 'REWORK')
            """)
    int closeOrder(
            @Param("orderId") UUID orderId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("closedBy") String closedBy,
            @Param("reason") String reason,
            @Param("at") OffsetDateTime at
    );

    // ---- 报价明细 ----

    @Insert("""
            INSERT INTO service_order_line (order_id, sort_order, name, quantity, unit, unit_amount_minor, amount_minor)
            VALUES (#{orderId}, #{sortOrder}, #{name}, #{quantity}, #{unit}, #{unitAmountMinor}, #{amountMinor})
            """)
    int insertLine(
            @Param("orderId") UUID orderId,
            @Param("sortOrder") int sortOrder,
            @Param("name") String name,
            @Param("quantity") Integer quantity,
            @Param("unit") String unit,
            @Param("unitAmountMinor") Long unitAmountMinor,
            @Param("amountMinor") long amountMinor
    );

    @Select("""
            SELECT order_id, sort_order, name, quantity, unit, unit_amount_minor, amount_minor
            FROM service_order_line
            WHERE order_id = #{orderId}
            ORDER BY sort_order
            """)
    List<LineRow> findLines(@Param("orderId") UUID orderId);

    // ---- 方案 ----

    @Insert("""
            INSERT INTO service_order_plan (
                order_id, plan_version, amount_minor, schedule_text, terms_text, note, issued_by, issued_at
            )
            VALUES (#{orderId}, #{planVersion}, #{amountMinor}, #{schedule}, #{terms}, #{note}, #{issuedBy}, #{at})
            """)
    int insertPlan(
            @Param("orderId") UUID orderId,
            @Param("planVersion") int planVersion,
            @Param("amountMinor") long amountMinor,
            @Param("schedule") String schedule,
            @Param("terms") String terms,
            @Param("note") String note,
            @Param("issuedBy") UUID issuedBy,
            @Param("at") OffsetDateTime at
    );

    @Select("""
            SELECT order_id, plan_version, amount_minor, schedule_text, terms_text, note, issued_at, confirmed_by_name,
                   confirmed_at
            FROM service_order_plan
            WHERE order_id = #{orderId} AND plan_version = #{planVersion}
            """)
    PlanRow findPlan(@Param("orderId") UUID orderId, @Param("planVersion") int planVersion);

    @Update("""
            UPDATE service_order_plan
            SET confirmed_by = #{confirmedBy}, confirmed_by_name = #{confirmedByName}, confirmed_at = #{at}
            WHERE order_id = #{orderId} AND plan_version = #{planVersion} AND confirmed_at IS NULL
            """)
    int confirmPlan(
            @Param("orderId") UUID orderId,
            @Param("planVersion") int planVersion,
            @Param("confirmedBy") UUID confirmedBy,
            @Param("confirmedByName") String confirmedByName,
            @Param("at") OffsetDateTime at
    );

    // ---- 付款尝试 ----

    @Insert("""
            INSERT INTO service_order_payment (id, order_id, trade_order_id, amount_minor, pay_before, created_at)
            VALUES (#{id}, #{orderId}, #{tradeOrderId}, #{amountMinor}, #{payBefore}, #{at})
            """)
    int insertPayment(
            @Param("id") UUID id,
            @Param("orderId") UUID orderId,
            @Param("tradeOrderId") UUID tradeOrderId,
            @Param("amountMinor") long amountMinor,
            @Param("payBefore") OffsetDateTime payBefore,
            @Param("at") OffsetDateTime at
    );

    String PAYMENT_COLUMNS = """
            SELECT id, order_id, trade_order_id, amount_minor, pay_before, status, refused, paid_at, closed_at, created_at
            FROM service_order_payment
            """;

    @Select(PAYMENT_COLUMNS + "WHERE order_id = #{orderId} ORDER BY created_at, id")
    List<PaymentRow> findPayments(@Param("orderId") UUID orderId);

    /** 交易回调：付款尝试和交易订单一起对上才算。 */
    @Select(PAYMENT_COLUMNS + "WHERE id = #{attemptId} AND trade_order_id = #{tradeOrderId}")
    PaymentRow findPayment(@Param("attemptId") UUID attemptId, @Param("tradeOrderId") UUID tradeOrderId);

    @Update("""
            UPDATE service_order_payment
            SET status = 'PAID', paid_at = #{at}, closed_at = NULL, refused = false
            WHERE id = #{attemptId} AND status IN ('PENDING', 'CLOSED') AND refused = false
            """)
    int markPaymentPaid(@Param("attemptId") UUID attemptId, @Param("at") OffsetDateTime at);

    /** 待付的尝试关闭；refused 为 true 表示付晚了、交易模块会整笔退回。 */
    @Update("""
            UPDATE service_order_payment
            SET status = 'CLOSED', closed_at = #{at}, refused = (refused OR #{refused})
            WHERE id = #{attemptId} AND (status = 'PENDING' OR (status = 'CLOSED' AND #{refused}))
            """)
    int closePayment(@Param("attemptId") UUID attemptId, @Param("refused") boolean refused,
                     @Param("at") OffsetDateTime at);

    // ---- 退款 ----

    @Insert("""
            INSERT INTO service_order_refund (order_id, trade_refund_id, kind, amount_minor, reason, requested_by,
                                              requested_at)
            VALUES (#{orderId}, #{tradeRefundId}, #{kind}, #{amountMinor}, #{reason}, #{requestedBy}, #{at})
            ON CONFLICT (trade_refund_id) DO NOTHING
            """)
    int insertRefund(
            @Param("orderId") UUID orderId,
            @Param("tradeRefundId") UUID tradeRefundId,
            @Param("kind") String kind,
            @Param("amountMinor") long amountMinor,
            @Param("reason") String reason,
            @Param("requestedBy") UUID requestedBy,
            @Param("at") OffsetDateTime at
    );

    @Select("""
            SELECT trade_refund_id, kind, amount_minor, reason, requested_at
            FROM service_order_refund
            WHERE order_id = #{orderId}
            ORDER BY requested_at, id
            """)
    List<RefundRow> findRefunds(@Param("orderId") UUID orderId);

    // ---- 交付 ----

    @Insert("""
            INSERT INTO service_order_delivery (id, order_id, round, note, delivered_by, delivered_at)
            VALUES (#{id}, #{orderId}, #{round}, #{note}, #{deliveredBy}, #{at})
            """)
    int insertDelivery(
            @Param("id") UUID id,
            @Param("orderId") UUID orderId,
            @Param("round") int round,
            @Param("note") String note,
            @Param("deliveredBy") UUID deliveredBy,
            @Param("at") OffsetDateTime at
    );

    @Select("""
            SELECT id, order_id, round, note, status, delivered_at, reviewed_by_name, reviewed_at, review_note
            FROM service_order_delivery
            WHERE order_id = #{orderId}
            ORDER BY round
            """)
    List<DeliveryRow> findDeliveries(@Param("orderId") UUID orderId);

    /** 验收或退回：只改待验收的那一次。 */
    @Update("""
            UPDATE service_order_delivery
            SET status = #{status}, reviewed_by = #{reviewedBy}, reviewed_by_name = #{reviewedByName},
                reviewed_at = #{at}, review_note = #{reviewNote}
            WHERE id = #{deliveryId} AND status = 'SUBMITTED'
            """)
    int reviewDelivery(
            @Param("deliveryId") UUID deliveryId,
            @Param("status") String status,
            @Param("reviewedBy") UUID reviewedBy,
            @Param("reviewedByName") String reviewedByName,
            @Param("reviewNote") String reviewNote,
            @Param("at") OffsetDateTime at
    );

    // ---- 文件 ----

    @Insert("""
            INSERT INTO service_order_file (id, order_id, name, content_type, size_bytes, sha256, storage_key,
                                            uploaded_by, uploaded_at)
            VALUES (#{id}, #{orderId}, #{name}, #{contentType}, #{sizeBytes}, #{sha256}, #{storageKey},
                    #{uploadedBy}, #{at})
            """)
    int insertFile(
            @Param("id") UUID id,
            @Param("orderId") UUID orderId,
            @Param("name") String name,
            @Param("contentType") String contentType,
            @Param("sizeBytes") long sizeBytes,
            @Param("sha256") String sha256,
            @Param("storageKey") String storageKey,
            @Param("uploadedBy") UUID uploadedBy,
            @Param("at") OffsetDateTime at
    );

    String FILE_COLUMNS = """
            SELECT id, order_id, delivery_id, name, content_type, size_bytes, storage_key, uploaded_at
            FROM service_order_file
            """;

    @Select(FILE_COLUMNS + "WHERE order_id = #{orderId} ORDER BY uploaded_at, id")
    List<FileRow> findFiles(@Param("orderId") UUID orderId);

    @Select(FILE_COLUMNS + "WHERE order_id = #{orderId} AND id = #{fileId}")
    FileRow findFile(@Param("orderId") UUID orderId, @Param("fileId") UUID fileId);

    @Select("SELECT count(*) FROM service_order_file WHERE order_id = #{orderId} AND delivery_id IS NULL")
    int countStagedFiles(@Param("orderId") UUID orderId);

    @Update("""
            <script>
            UPDATE service_order_file
            SET delivery_id = #{deliveryId}
            WHERE order_id = #{orderId} AND delivery_id IS NULL AND id IN
            <foreach collection="fileIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int attachFiles(
            @Param("orderId") UUID orderId,
            @Param("deliveryId") UUID deliveryId,
            @Param("fileIds") List<UUID> fileIds
    );

    @Delete("DELETE FROM service_order_file WHERE order_id = #{orderId} AND id = #{fileId} AND delivery_id IS NULL")
    int deleteStagedFile(@Param("orderId") UUID orderId, @Param("fileId") UUID fileId);

    // ---- 下载令牌 ----

    @Insert("""
            INSERT INTO service_order_download (token, file_id, account_id, created_at, expires_at)
            VALUES (#{token}, #{fileId}, #{accountId}, #{at}, #{expiresAt})
            """)
    int insertDownload(
            @Param("token") UUID token,
            @Param("fileId") UUID fileId,
            @Param("accountId") UUID accountId,
            @Param("at") OffsetDateTime at,
            @Param("expiresAt") OffsetDateTime expiresAt
    );

    /** 还没过期的令牌对应的已交付文件。 */
    @Select("""
            SELECT f.id, f.order_id, f.delivery_id, f.name, f.content_type, f.size_bytes, f.storage_key, f.uploaded_at
            FROM service_order_download t
            JOIN service_order_file f ON f.id = t.file_id
            WHERE t.token = #{token} AND t.expires_at > #{now} AND f.delivery_id IS NOT NULL
            """)
    FileRow findDownload(@Param("token") UUID token, @Param("now") OffsetDateTime now);

    @Delete("DELETE FROM service_order_download WHERE expires_at < #{before}")
    int deleteExpiredDownloads(@Param("before") OffsetDateTime before);

    // ---- 订单记录 ----

    @Insert("""
            INSERT INTO service_order_event (order_id, event_type, actor_id, plan_version, round, occurred_at)
            VALUES (#{orderId}, #{type}, #{actorId}, #{planVersion}, #{round}, #{at})
            """)
    int insertEvent(
            @Param("orderId") UUID orderId,
            @Param("type") String type,
            @Param("actorId") UUID actorId,
            @Param("planVersion") Integer planVersion,
            @Param("round") Integer round,
            @Param("at") OffsetDateTime at
    );

    @Select("""
            SELECT event_type, plan_version, round, occurred_at
            FROM service_order_event
            WHERE order_id = #{orderId}
            ORDER BY occurred_at, created_at, id
            """)
    List<EventRow> findEvents(@Param("orderId") UUID orderId);

    // ---- 行 ----

    record OrderRow(
            UUID id,
            String orderNo,
            UUID demandId,
            UUID quoteId,
            int quoteVersion,
            UUID accountId,
            String customerKind,
            String customerOrganizationName,
            String contactName,
            UUID providerId,
            String providerName,
            String type,
            String title,
            String cityCode,
            String cityName,
            String address,
            String scopeText,
            LocalDate dueDate,
            long amountMinor,
            String quoteMethod,
            String quoteSchedule,
            int quoteDurationDays,
            String quoteDeliverables,
            String quoteTeam,
            String quoteNightWork,
            Integer quoteReportHours,
            Integer quoteArchiveDays,
            LocalDate quoteFirstVisitDate,
            String status,
            Integer planVersion,
            OffsetDateTime paidAt,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            OffsetDateTime closedAt,
            String closedBy,
            String closeReason,
            OffsetDateTime latestDeliveryAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version
    ) {
    }

    record LineRow(
            UUID orderId,
            int sortOrder,
            String name,
            Integer quantity,
            String unit,
            Long unitAmountMinor,
            long amountMinor
    ) {
    }

    record PlanRow(
            UUID orderId,
            int planVersion,
            long amountMinor,
            String scheduleText,
            String termsText,
            String note,
            OffsetDateTime issuedAt,
            String confirmedByName,
            OffsetDateTime confirmedAt
    ) {
    }

    record PaymentRow(
            UUID id,
            UUID orderId,
            UUID tradeOrderId,
            long amountMinor,
            OffsetDateTime payBefore,
            String status,
            boolean refused,
            OffsetDateTime paidAt,
            OffsetDateTime closedAt,
            OffsetDateTime createdAt
    ) {
    }

    record RefundRow(
            UUID tradeRefundId,
            String kind,
            long amountMinor,
            String reason,
            OffsetDateTime requestedAt
    ) {
    }

    record DeliveryRow(
            UUID id,
            UUID orderId,
            int round,
            String note,
            String status,
            OffsetDateTime deliveredAt,
            String reviewedByName,
            OffsetDateTime reviewedAt,
            String reviewNote
    ) {
    }

    record FileRow(
            UUID id,
            UUID orderId,
            UUID deliveryId,
            String name,
            String contentType,
            long sizeBytes,
            String storageKey,
            OffsetDateTime uploadedAt
    ) {
    }

    record EventRow(
            String eventType,
            Integer planVersion,
            Integer round,
            OffsetDateTime occurredAt
    ) {
    }
}
