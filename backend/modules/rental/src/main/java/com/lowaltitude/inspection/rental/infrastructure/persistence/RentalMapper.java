package com.lowaltitude.inspection.rental.infrastructure.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 询价单、报价、租赁订单、合同、交接归还记录的读写（F3）。全部用绑定参数；客户的读写以账号为条件，商家的读写以出租机构为条件。
 * “当前时间”由调用方传入。加锁顺序：交易订单（交易模块）→ 租赁订单；询价单单独锁。
 */
public interface RentalMapper {

    // ---- 询价单 ----

    @Select("""
            SELECT 1 FROM (
                SELECT pg_advisory_xact_lock(hashtextextended('rental:' || CAST(#{accountId} AS text), 0))
            ) locked
            """)
    Integer lockAccount(@Param("accountId") UUID accountId);

    @Select("""
            SELECT id, request_hash
            FROM rental_inquiry
            WHERE account_id = #{accountId}
              AND idempotency_key = #{idempotencyKey}
            """)
    KeyRow findByKey(@Param("accountId") UUID accountId, @Param("idempotencyKey") UUID idempotencyKey);

    @Insert("""
            INSERT INTO rental_inquiry (
                id, account_id, lessee_kind, lessee_organization_id, lessee_organization_name, lessee_contact_name,
                offer_id, product_id, product_name, sku_name, cover_url, merchant_id, merchant_name,
                reference_amount_minor, reference_unit, quantity, start_date, days, handover_mode, return_mode,
                address_id, recipient_name, recipient_phone, province_name, city_code, city_name, address_detail, note,
                quote_deadline, idempotency_key, request_hash, created_at, updated_at
            )
            VALUES (
                #{id}, #{accountId}, #{lesseeKind}, #{lesseeOrganizationId}, #{lesseeOrganizationName},
                #{lesseeContactName}, #{offerId}, #{productId}, #{productName}, #{skuName}, #{coverUrl}, #{merchantId},
                #{merchantName}, #{referenceAmountMinor}, #{referenceUnit}, #{quantity}, #{startDate}, #{days},
                #{handoverMode}, #{returnMode}, #{addressId}, #{recipientName}, #{recipientPhone}, #{provinceName},
                #{cityCode}, #{cityName}, #{addressDetail}, #{note}, #{quoteDeadline}, #{idempotencyKey}, #{requestHash},
                #{at}, #{at}
            )
            """)
    int insertInquiry(
            @Param("id") UUID id,
            @Param("accountId") UUID accountId,
            @Param("lesseeKind") String lesseeKind,
            @Param("lesseeOrganizationId") UUID lesseeOrganizationId,
            @Param("lesseeOrganizationName") String lesseeOrganizationName,
            @Param("lesseeContactName") String lesseeContactName,
            @Param("offerId") UUID offerId,
            @Param("productId") UUID productId,
            @Param("productName") String productName,
            @Param("skuName") String skuName,
            @Param("coverUrl") String coverUrl,
            @Param("merchantId") UUID merchantId,
            @Param("merchantName") String merchantName,
            @Param("referenceAmountMinor") Long referenceAmountMinor,
            @Param("referenceUnit") String referenceUnit,
            @Param("quantity") int quantity,
            @Param("startDate") LocalDate startDate,
            @Param("days") int days,
            @Param("handoverMode") String handoverMode,
            @Param("returnMode") String returnMode,
            @Param("addressId") UUID addressId,
            @Param("recipientName") String recipientName,
            @Param("recipientPhone") String recipientPhone,
            @Param("provinceName") String provinceName,
            @Param("cityCode") String cityCode,
            @Param("cityName") String cityName,
            @Param("addressDetail") String addressDetail,
            @Param("note") String note,
            @Param("quoteDeadline") OffsetDateTime quoteDeadline,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("at") OffsetDateTime at
    );

    String INQUIRY_COLUMNS = """
            SELECT i.id,
                   i.account_id,
                   i.lessee_kind,
                   i.lessee_organization_name,
                   i.lessee_contact_name,
                   i.offer_id,
                   i.product_id,
                   i.product_name,
                   i.sku_name,
                   i.cover_url,
                   i.merchant_id,
                   i.merchant_name,
                   i.reference_amount_minor,
                   i.reference_unit,
                   i.quantity,
                   i.start_date,
                   i.days,
                   i.handover_mode,
                   i.return_mode,
                   i.address_id,
                   i.recipient_name,
                   i.recipient_phone,
                   i.province_name,
                   i.city_code,
                   i.city_name,
                   i.address_detail,
                   i.note,
                   i.status,
                   i.close_reason,
                   i.decline_reason,
                   i.current_quote_version,
                   i.quote_deadline,
                   i.created_at,
                   i.updated_at,
                   i.version
            FROM rental_inquiry i
            """;

    @Select(INQUIRY_COLUMNS + "WHERE i.id = #{inquiryId} AND i.account_id = #{accountId}")
    InquiryRow findInquiry(@Param("accountId") UUID accountId, @Param("inquiryId") UUID inquiryId);

    @Select(INQUIRY_COLUMNS + "WHERE i.id = #{inquiryId} AND i.account_id = #{accountId} FOR UPDATE")
    InquiryRow lockInquiry(@Param("accountId") UUID accountId, @Param("inquiryId") UUID inquiryId);

    @Select(INQUIRY_COLUMNS + "WHERE i.id = #{inquiryId} AND i.merchant_id = #{merchantId}")
    InquiryRow findMerchantInquiry(@Param("merchantId") UUID merchantId, @Param("inquiryId") UUID inquiryId);

    @Select(INQUIRY_COLUMNS + "WHERE i.id = #{inquiryId} AND i.merchant_id = #{merchantId} FOR UPDATE")
    InquiryRow lockMerchantInquiry(@Param("merchantId") UUID merchantId, @Param("inquiryId") UUID inquiryId);

    /** 我的询价单：status 为空时全部。最近更新的在前。 */
    String ACCOUNT_INQUIRIES = """
            WHERE i.account_id = #{accountId}
              AND (CAST(#{status} AS varchar) IS NULL OR i.status = #{status})
            """;

    @Select("SELECT count(*) FROM rental_inquiry i " + ACCOUNT_INQUIRIES)
    long countInquiries(@Param("accountId") UUID accountId, @Param("status") String status);

    @Select(INQUIRY_COLUMNS + ACCOUNT_INQUIRIES + """
            ORDER BY i.updated_at DESC, i.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<InquiryRow> findInquiryPage(
            @Param("accountId") UUID accountId,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /** 出租方收到的询价单：新提交的在前。 */
    String MERCHANT_INQUIRIES = """
            WHERE i.merchant_id = #{merchantId}
              AND (CAST(#{status} AS varchar) IS NULL OR i.status = #{status})
            """;

    @Select("SELECT count(*) FROM rental_inquiry i " + MERCHANT_INQUIRIES)
    long countMerchantInquiries(@Param("merchantId") UUID merchantId, @Param("status") String status);

    @Select(INQUIRY_COLUMNS + MERCHANT_INQUIRIES + """
            ORDER BY i.created_at DESC, i.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<InquiryRow> findMerchantInquiryPage(
            @Param("merchantId") UUID merchantId,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /** 关闭：只关还在待报价、待确认、报价已过期的。 */
    @Update("""
            UPDATE rental_inquiry
            SET status = 'CLOSED',
                close_reason = #{reason},
                closed_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{inquiryId}
              AND status IN ('PENDING', 'QUOTED', 'EXPIRED')
            """)
    int closeInquiry(@Param("inquiryId") UUID inquiryId, @Param("reason") String reason, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE rental_inquiry
            SET status = 'DECLINED',
                decline_reason = #{reason},
                declined_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{inquiryId}
              AND status IN ('PENDING', 'QUOTED', 'EXPIRED')
            """)
    int declineInquiry(@Param("inquiryId") UUID inquiryId, @Param("reason") String reason, @Param("at") OffsetDateTime at);

    /** 报价或改价：询价单成为待确认，指向最新一版。 */
    @Update("""
            UPDATE rental_inquiry
            SET status = 'QUOTED',
                current_quote_version = #{quoteVersion},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{inquiryId}
              AND status IN ('PENDING', 'QUOTED', 'EXPIRED')
            """)
    int markQuoted(@Param("inquiryId") UUID inquiryId, @Param("quoteVersion") int quoteVersion,
                   @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE rental_inquiry
            SET status = 'EXPIRED',
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{inquiryId}
              AND status = 'QUOTED'
            """)
    int expireInquiry(@Param("inquiryId") UUID inquiryId, @Param("at") OffsetDateTime at);

    /** 确认合同：只确认版本对得上、还在待确认的那一份。 */
    @Update("""
            UPDATE rental_inquiry
            SET status = 'CONFIRMED',
                confirmed_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{inquiryId}
              AND status = 'QUOTED'
              AND version = #{version}
            """)
    int confirmInquiry(@Param("inquiryId") UUID inquiryId, @Param("version") int version,
                       @Param("at") OffsetDateTime at);

    /** 定时任务：过了报价截止还没有报价的，跳过别的实例正在处理的行。 */
    @Select(INQUIRY_COLUMNS + """
            WHERE i.status = 'PENDING'
              AND i.quote_deadline <= #{now}
            ORDER BY i.quote_deadline ASC, i.id ASC
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<InquiryRow> lockUnquoted(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    /** 定时任务：最新一版报价过了有效期、还没确认的。 */
    @Select(INQUIRY_COLUMNS + """
            JOIN rental_quote q
              ON q.inquiry_id = i.id
             AND q.quote_version = i.current_quote_version
            WHERE i.status = 'QUOTED'
              AND q.valid_until <= #{now}
            ORDER BY q.valid_until ASC, i.id ASC
            LIMIT #{limit}
            FOR UPDATE OF i SKIP LOCKED
            """)
    List<InquiryRow> lockLapsedQuotes(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    // ---- 报价 ----

    @Select("""
            INSERT INTO rental_quote (
                inquiry_id, quote_version, daily_rent_minor, days, quantity, rent_minor, total_minor,
                schedule_available, valid_until, terms, quoted_by, quoted_at, created_at
            )
            VALUES (
                #{inquiryId}, #{quoteVersion}, #{dailyRentMinor}, #{days}, #{quantity}, #{rentMinor}, #{totalMinor},
                #{scheduleAvailable}, #{validUntil}, #{terms}, #{quotedBy}, #{at}, #{at}
            )
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertQuote(
            @Param("inquiryId") UUID inquiryId,
            @Param("quoteVersion") int quoteVersion,
            @Param("dailyRentMinor") long dailyRentMinor,
            @Param("days") int days,
            @Param("quantity") int quantity,
            @Param("rentMinor") long rentMinor,
            @Param("totalMinor") long totalMinor,
            @Param("scheduleAvailable") boolean scheduleAvailable,
            @Param("validUntil") OffsetDateTime validUntil,
            @Param("terms") String terms,
            @Param("quotedBy") UUID quotedBy,
            @Param("at") OffsetDateTime at
    );

    @Insert("""
            INSERT INTO rental_quote_fee (quote_id, name, note, amount_minor, sort_order)
            VALUES (#{quoteId}, #{name}, #{note}, #{amountMinor}, #{sortOrder})
            """)
    int insertFee(
            @Param("quoteId") UUID quoteId,
            @Param("name") String name,
            @Param("note") String note,
            @Param("amountMinor") long amountMinor,
            @Param("sortOrder") int sortOrder
    );

    String QUOTE_COLUMNS = """
            SELECT q.id,
                   q.inquiry_id,
                   q.quote_version,
                   q.daily_rent_minor,
                   q.days,
                   q.quantity,
                   q.rent_minor,
                   q.total_minor,
                   q.schedule_available,
                   q.valid_until,
                   q.terms,
                   q.quoted_at
            FROM rental_quote q
            """;

    @Select(QUOTE_COLUMNS + "WHERE q.inquiry_id = #{inquiryId} AND q.quote_version = #{quoteVersion}")
    QuoteRow findQuote(@Param("inquiryId") UUID inquiryId, @Param("quoteVersion") int quoteVersion);

    /** 这些询价单各自最新的一版报价。 */
    @Select("<script>" + QUOTE_COLUMNS + """
            JOIN rental_inquiry i
              ON i.id = q.inquiry_id
             AND i.current_quote_version = q.quote_version
            WHERE q.inquiry_id IN
            <foreach collection="inquiryIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<QuoteRow> findCurrentQuotes(@Param("inquiryIds") List<UUID> inquiryIds);

    @Select("""
            <script>
            SELECT quote_id, name, note, amount_minor
            FROM rental_quote_fee
            WHERE quote_id IN
            <foreach collection="quoteIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY quote_id, sort_order, id
            </script>
            """)
    List<FeeRow> findFees(@Param("quoteIds") List<UUID> quoteIds);

    @Select("""
            <script>
            SELECT inquiry_id, id rental_order_id, status, trade_order_id
            FROM rental_order
            WHERE inquiry_id IN
            <foreach collection="inquiryIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<OrderSummaryRow> findOrderSummaries(@Param("inquiryIds") List<UUID> inquiryIds);

    // ---- 租赁订单和合同 ----

    @Insert("""
            INSERT INTO rental_order (
                id, inquiry_id, quote_id, account_id, merchant_id, trade_order_id, total_minor, pay_before,
                created_at, updated_at
            )
            VALUES (
                #{id}, #{inquiryId}, #{quoteId}, #{accountId}, #{merchantId}, #{tradeOrderId}, #{totalMinor},
                #{payBefore}, #{at}, #{at}
            )
            """)
    int insertOrder(
            @Param("id") UUID id,
            @Param("inquiryId") UUID inquiryId,
            @Param("quoteId") UUID quoteId,
            @Param("accountId") UUID accountId,
            @Param("merchantId") UUID merchantId,
            @Param("tradeOrderId") UUID tradeOrderId,
            @Param("totalMinor") long totalMinor,
            @Param("payBefore") OffsetDateTime payBefore,
            @Param("at") OffsetDateTime at
    );

    /** 合同快照：询价单的身份、设备、地点，报价的金额、天数、台数、条款。 */
    @Insert("""
            INSERT INTO rental_contract (
                order_id, contract_no, quote_id, quote_version, lessee_kind, lessee_organization_name,
                lessee_contact_name, lessor_id, lessor_name, product_name, sku_name, quantity, start_date, end_date,
                days, daily_rent_minor, rent_minor, total_minor, handover_mode, return_mode, recipient_name,
                recipient_phone, province_name, city_code, city_name, address_detail, terms, confirmed_at
            )
            SELECT #{orderId}, #{contractNo}, q.id, q.quote_version, i.lessee_kind, i.lessee_organization_name,
                   i.lessee_contact_name, i.merchant_id, i.merchant_name, i.product_name, i.sku_name, q.quantity,
                   i.start_date, i.start_date + q.days - 1, q.days, q.daily_rent_minor, q.rent_minor, q.total_minor,
                   i.handover_mode, i.return_mode, i.recipient_name, i.recipient_phone, i.province_name, i.city_code,
                   i.city_name, i.address_detail, q.terms, #{at}
            FROM rental_inquiry i
            JOIN rental_quote q ON q.inquiry_id = i.id AND q.id = #{quoteId}
            WHERE i.id = #{inquiryId}
            """)
    int insertContract(
            @Param("orderId") UUID orderId,
            @Param("contractNo") String contractNo,
            @Param("inquiryId") UUID inquiryId,
            @Param("quoteId") UUID quoteId,
            @Param("at") OffsetDateTime at
    );

    String ORDER_COLUMNS = """
            SELECT o.id,
                   o.inquiry_id,
                   o.quote_id,
                   o.account_id,
                   o.merchant_id,
                   o.trade_order_id,
                   o.status,
                   o.total_minor,
                   o.pay_before,
                   o.paid_at,
                   o.closed_at,
                   o.rent_started_at,
                   o.ended_at,
                   o.cancelled_at,
                   o.cancel_reason,
                   o.refund_id,
                   o.created_at,
                   o.updated_at,
                   o.version
            FROM rental_order o
            """;

    @Select(ORDER_COLUMNS + "WHERE o.id = #{orderId} AND o.account_id = #{accountId}")
    OrderRow findOrder(@Param("accountId") UUID accountId, @Param("orderId") UUID orderId);

    @Select(ORDER_COLUMNS + "WHERE o.id = #{orderId} AND o.account_id = #{accountId} FOR UPDATE")
    OrderRow lockOrder(@Param("accountId") UUID accountId, @Param("orderId") UUID orderId);

    @Select(ORDER_COLUMNS + "WHERE o.id = #{orderId} AND o.merchant_id = #{merchantId}")
    OrderRow findMerchantOrder(@Param("merchantId") UUID merchantId, @Param("orderId") UUID orderId);

    @Select(ORDER_COLUMNS + "WHERE o.id = #{orderId} AND o.merchant_id = #{merchantId} FOR UPDATE")
    OrderRow lockMerchantOrder(@Param("merchantId") UUID merchantId, @Param("orderId") UUID orderId);

    /** 交易回调：按租赁订单 ID 和交易订单 ID 一起找，两者对不上当作不存在。 */
    @Select(ORDER_COLUMNS + "WHERE o.id = #{orderId} AND o.trade_order_id = #{tradeOrderId} FOR UPDATE")
    OrderRow lockPaidOrder(@Param("orderId") UUID orderId, @Param("tradeOrderId") UUID tradeOrderId);

    @Select("<script>" + ORDER_COLUMNS + """
            WHERE o.inquiry_id IN
            <foreach collection="inquiryIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<OrderRow> findOrdersOfInquiries(@Param("inquiryIds") List<UUID> inquiryIds);

    String ACCOUNT_ORDERS = """
            WHERE o.account_id = #{accountId}
              AND (CAST(#{status} AS varchar) IS NULL OR o.status = #{status})
            """;

    @Select("SELECT count(*) FROM rental_order o " + ACCOUNT_ORDERS)
    long countOrders(@Param("accountId") UUID accountId, @Param("status") String status);

    @Select(ORDER_COLUMNS + ACCOUNT_ORDERS + """
            ORDER BY o.created_at DESC, o.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<OrderRow> findOrderPage(
            @Param("accountId") UUID accountId,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    String MERCHANT_ORDERS = """
            WHERE o.merchant_id = #{merchantId}
              AND (CAST(#{status} AS varchar) IS NULL OR o.status = #{status})
            """;

    @Select("SELECT count(*) FROM rental_order o " + MERCHANT_ORDERS)
    long countMerchantOrders(@Param("merchantId") UUID merchantId, @Param("status") String status);

    @Select(ORDER_COLUMNS + MERCHANT_ORDERS + """
            ORDER BY o.created_at DESC, o.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<OrderRow> findMerchantOrderPage(
            @Param("merchantId") UUID merchantId,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /** 付款到账：待付款 → 待交接。 */
    @Update("""
            UPDATE rental_order
            SET status = 'AWAITING_HANDOVER',
                paid_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{orderId}
              AND status = 'PENDING_PAYMENT'
            """)
    int markPaid(@Param("orderId") UUID orderId, @Param("at") OffsetDateTime at);

    /** 没付款就关闭（超时或取消付款）。 */
    @Update("""
            UPDATE rental_order
            SET status = 'CLOSED',
                closed_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{orderId}
              AND status = 'PENDING_PAYMENT'
            """)
    int closeUnpaid(@Param("orderId") UUID orderId, @Param("at") OffsetDateTime at);

    /** 交接、归还的状态推进：只改还在 fromStatus 的。 */
    @Update("""
            <script>
            UPDATE rental_order
            SET status = #{toStatus},
                <if test="toStatus == 'IN_USE'">rent_started_at = #{at},</if>
                <if test="toStatus == 'ENDED'">ended_at = #{at},</if>
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{orderId}
              AND status = #{fromStatus}
            </script>
            """)
    int moveOrder(
            @Param("orderId") UUID orderId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("at") OffsetDateTime at
    );

    /** 交接前商家取消：整单退款。 */
    @Update("""
            UPDATE rental_order
            SET status = 'CANCELLED',
                cancelled_at = #{at},
                cancel_reason = #{reason},
                refund_id = #{refundId},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{orderId}
              AND status IN ('AWAITING_HANDOVER', 'HANDOVER_RECORDED')
            """)
    int cancelOrder(
            @Param("orderId") UUID orderId,
            @Param("reason") String reason,
            @Param("refundId") UUID refundId,
            @Param("at") OffsetDateTime at
    );

    String CONTRACT_COLUMNS = """
            SELECT c.order_id,
                   c.contract_no,
                   c.quote_id,
                   c.quote_version,
                   c.lessee_kind,
                   c.lessee_organization_name,
                   c.lessee_contact_name,
                   c.lessor_id,
                   c.lessor_name,
                   c.product_name,
                   c.sku_name,
                   c.quantity,
                   c.start_date,
                   c.end_date,
                   c.days,
                   c.daily_rent_minor,
                   c.rent_minor,
                   c.total_minor,
                   c.handover_mode,
                   c.return_mode,
                   c.recipient_name,
                   c.recipient_phone,
                   c.province_name,
                   c.city_code,
                   c.city_name,
                   c.address_detail,
                   c.terms,
                   c.confirmed_at
            FROM rental_contract c
            """;

    @Select("<script>" + CONTRACT_COLUMNS + """
            WHERE c.order_id IN
            <foreach collection="orderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<ContractRow> findContracts(@Param("orderIds") List<UUID> orderIds);

    // ---- 交接和归还记录 ----

    /** 商家录入或改记录：客户确认之后不再改（返回 0）。 */
    @Insert("""
            INSERT INTO rental_record (order_id, kind, occurred_at, note, recorded_by, recorded_at, created_at, updated_at)
            VALUES (#{orderId}, #{kind}, #{occurredAt}, #{note}, #{recordedBy}, #{at}, #{at}, #{at})
            ON CONFLICT (order_id, kind) DO UPDATE
            SET occurred_at = EXCLUDED.occurred_at,
                note = EXCLUDED.note,
                recorded_by = EXCLUDED.recorded_by,
                recorded_at = EXCLUDED.recorded_at,
                version = rental_record.version + 1,
                updated_at = EXCLUDED.updated_at
            WHERE rental_record.confirmed_at IS NULL
            """)
    int saveRecord(
            @Param("orderId") UUID orderId,
            @Param("kind") String kind,
            @Param("occurredAt") OffsetDateTime occurredAt,
            @Param("note") String note,
            @Param("recordedBy") UUID recordedBy,
            @Param("at") OffsetDateTime at
    );

    @Update("""
            UPDATE rental_record
            SET confirmed_by = #{confirmedBy},
                confirmed_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE order_id = #{orderId}
              AND kind = #{kind}
              AND confirmed_at IS NULL
            """)
    int confirmRecord(
            @Param("orderId") UUID orderId,
            @Param("kind") String kind,
            @Param("confirmedBy") UUID confirmedBy,
            @Param("at") OffsetDateTime at
    );

    @Select("""
            <script>
            SELECT order_id, kind, occurred_at, note, recorded_at, confirmed_at
            FROM rental_record
            WHERE order_id IN
            <foreach collection="orderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<RecordRow> findRecords(@Param("orderIds") List<UUID> orderIds);

    // ---- 记录 ----

    @Insert("""
            INSERT INTO rental_event (inquiry_id, order_id, event_type, actor_id, quote_version, occurred_at)
            VALUES (#{inquiryId}, #{orderId}, #{eventType}, #{actorId}, #{quoteVersion}, #{at})
            """)
    int insertEvent(
            @Param("inquiryId") UUID inquiryId,
            @Param("orderId") UUID orderId,
            @Param("eventType") String eventType,
            @Param("actorId") UUID actorId,
            @Param("quoteVersion") Integer quoteVersion,
            @Param("at") OffsetDateTime at
    );

    // ---- 行 ----

    record KeyRow(UUID id, String requestHash) {
    }

    record InquiryRow(
            UUID id,
            UUID accountId,
            String lesseeKind,
            String lesseeOrganizationName,
            String lesseeContactName,
            UUID offerId,
            UUID productId,
            String productName,
            String skuName,
            String coverUrl,
            UUID merchantId,
            String merchantName,
            Long referenceAmountMinor,
            String referenceUnit,
            int quantity,
            LocalDate startDate,
            int days,
            String handoverMode,
            String returnMode,
            UUID addressId,
            String recipientName,
            String recipientPhone,
            String provinceName,
            String cityCode,
            String cityName,
            String addressDetail,
            String note,
            String status,
            String closeReason,
            String declineReason,
            Integer currentQuoteVersion,
            OffsetDateTime quoteDeadline,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version
    ) {
    }

    record QuoteRow(
            UUID id,
            UUID inquiryId,
            int quoteVersion,
            long dailyRentMinor,
            int days,
            int quantity,
            long rentMinor,
            long totalMinor,
            boolean scheduleAvailable,
            OffsetDateTime validUntil,
            String terms,
            OffsetDateTime quotedAt
    ) {
    }

    record FeeRow(UUID quoteId, String name, String note, long amountMinor) {
    }

    record OrderSummaryRow(UUID inquiryId, UUID rentalOrderId, String status, UUID tradeOrderId) {
    }

    record OrderRow(
            UUID id,
            UUID inquiryId,
            UUID quoteId,
            UUID accountId,
            UUID merchantId,
            UUID tradeOrderId,
            String status,
            long totalMinor,
            OffsetDateTime payBefore,
            OffsetDateTime paidAt,
            OffsetDateTime closedAt,
            OffsetDateTime rentStartedAt,
            OffsetDateTime endedAt,
            OffsetDateTime cancelledAt,
            String cancelReason,
            UUID refundId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version
    ) {
    }

    record ContractRow(
            UUID orderId,
            String contractNo,
            UUID quoteId,
            int quoteVersion,
            String lesseeKind,
            String lesseeOrganizationName,
            String lesseeContactName,
            UUID lessorId,
            String lessorName,
            String productName,
            String skuName,
            int quantity,
            LocalDate startDate,
            LocalDate endDate,
            int days,
            long dailyRentMinor,
            long rentMinor,
            long totalMinor,
            String handoverMode,
            String returnMode,
            String recipientName,
            String recipientPhone,
            String provinceName,
            String cityCode,
            String cityName,
            String addressDetail,
            String terms,
            OffsetDateTime confirmedAt
    ) {
    }

    record RecordRow(UUID orderId, String kind, OffsetDateTime occurredAt, String note, OffsetDateTime recordedAt,
                     OffsetDateTime confirmedAt) {
    }
}
