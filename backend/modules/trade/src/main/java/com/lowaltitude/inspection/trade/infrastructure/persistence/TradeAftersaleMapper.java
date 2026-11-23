package com.lowaltitude.inspection.trade.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 商品售后（S4-2，迁移 V010）。买家入口以 buyer_id、商家入口以 merchant_id 为条件，别人的售后单查不到。
 * 锁的顺序和退款一样：先订单（TradeRefundMapper.lock…Order），再售后单。“当前时间”由调用方传入。
 */
public interface TradeAftersaleMapper {

    String COLUMNS = """
            SELECT a.id, a.aftersale_no, a.order_id, o.order_no, a.buyer_id, a.merchant_id, o.merchant_name,
                   o.aftersale_party, a.kind, a.reason, a.description, a.requested_minor, a.refund_minor, a.status,
                   a.return_contact, a.return_phone, a.return_address, a.return_note, a.return_carrier,
                   a.return_tracking_no, a.reject_reason, a.merchant_note, a.refund_id, a.due_at, a.submitted_at,
                   a.reviewed_at, a.return_shipped_at, a.received_at, a.refunded_at, a.closed_at, a.idempotency_key,
                   a.request_hash, a.version, o.paid_minor
            FROM trade_aftersale a
            JOIN trade_order o ON o.id = a.order_id
            """;

    // ---- 申请 ----

    @Select(COLUMNS + "WHERE a.buyer_id = #{buyerId} AND a.idempotency_key = #{idempotencyKey}")
    AftersaleRow findByKey(@Param("buyerId") UUID buyerId, @Param("idempotencyKey") UUID idempotencyKey);

    @Select("SELECT count(*) FROM trade_aftersale WHERE aftersale_no = #{aftersaleNo}")
    int countAftersaleNo(@Param("aftersaleNo") String aftersaleNo);

    @Select("SELECT count(*) FROM trade_aftersale WHERE order_id = #{orderId}")
    int countForOrder(@Param("orderId") UUID orderId);

    @Select("""
            SELECT count(*) FROM trade_aftersale
            WHERE order_id = #{orderId}
              AND status IN ('PENDING_REVIEW', 'AWAITING_RETURN', 'RETURN_SHIPPED', 'REFUNDING')
            """)
    int countOpenForOrder(@Param("orderId") UUID orderId);

    /** 订单完成（确认收货、自提核销）时间和是否每件都支持七天无理由：判断能不能申请用。 */
    @Select("""
            SELECT o.source,
                   o.completed_at,
                   o.shipped_at,
                   NOT EXISTS (SELECT 1 FROM trade_order_line l WHERE l.order_id = o.id AND NOT l.no_reason_return)
                       no_reason_return
            FROM trade_order o
            WHERE o.id = #{orderId}
            """)
    OrderFactsRow findOrderFacts(@Param("orderId") UUID orderId);

    @Select("""
            INSERT INTO trade_aftersale (
                aftersale_no, order_id, buyer_id, merchant_id, kind, reason, description, requested_minor, status,
                due_at, submitted_at, idempotency_key, request_hash, created_at, updated_at
            )
            VALUES (
                #{aftersaleNo}, #{orderId}, #{buyerId}, #{merchantId}, #{kind}, #{reason}, #{description},
                #{requestedMinor}, #{status}, #{dueAt}, #{at}, #{idempotencyKey}, #{requestHash}, #{at}, #{at}
            )
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertAftersale(
            @Param("aftersaleNo") String aftersaleNo,
            @Param("orderId") UUID orderId,
            @Param("buyerId") UUID buyerId,
            @Param("merchantId") UUID merchantId,
            @Param("kind") String kind,
            @Param("reason") String reason,
            @Param("description") String description,
            @Param("requestedMinor") long requestedMinor,
            @Param("status") String status,
            @Param("dueAt") Instant dueAt,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("at") Instant at
    );

    // ---- 读 ----

    @Select(COLUMNS + "WHERE a.id = #{id} AND a.buyer_id = #{buyerId}")
    AftersaleRow findBuyerAftersale(@Param("buyerId") UUID buyerId, @Param("id") UUID id);

    @Select(COLUMNS + "WHERE a.id = #{id} AND a.merchant_id = #{merchantId}")
    AftersaleRow findMerchantAftersale(@Param("merchantId") UUID merchantId, @Param("id") UUID id);

    @Select(COLUMNS + "WHERE a.id = #{id}")
    AftersaleRow findAftersale(@Param("id") UUID id);

    @Select(COLUMNS + "WHERE a.id = #{id} FOR UPDATE OF a")
    AftersaleRow lockAftersale(@Param("id") UUID id);

    @Select(COLUMNS + "WHERE a.refund_id = #{refundId} FOR UPDATE OF a")
    AftersaleRow lockByRefund(@Param("refundId") UUID refundId);

    String BUYER_FILTER = """
            WHERE a.buyer_id = #{buyerId}
            <if test="statuses != null and statuses.size() > 0">
              AND a.status IN
              <foreach collection="statuses" item="s" open="(" separator="," close=")">#{s}</foreach>
            </if>
            """;

    @Select("<script>SELECT count(*) FROM trade_aftersale a " + BUYER_FILTER + "</script>")
    long countBuyerAftersales(@Param("buyerId") UUID buyerId, @Param("statuses") List<String> statuses);

    @Select("<script>" + COLUMNS + BUYER_FILTER + """
            ORDER BY a.submitted_at DESC, a.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AftersaleRow> findBuyerAftersales(
            @Param("buyerId") UUID buyerId,
            @Param("statuses") List<String> statuses,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select("""
            SELECT status, count(*) total FROM trade_aftersale
            WHERE buyer_id = #{buyerId}
            GROUP BY status
            """)
    List<StatusCountRow> countBuyerByStatus(@Param("buyerId") UUID buyerId);

    String MERCHANT_FILTER = """
            WHERE a.merchant_id = #{merchantId}
            <if test="statuses != null and statuses.size() > 0">
              AND a.status IN
              <foreach collection="statuses" item="s" open="(" separator="," close=")">#{s}</foreach>
            </if>
            """;

    @Select("<script>SELECT count(*) FROM trade_aftersale a " + MERCHANT_FILTER + "</script>")
    long countMerchantAftersales(@Param("merchantId") UUID merchantId, @Param("statuses") List<String> statuses);

    @Select("<script>" + COLUMNS + MERCHANT_FILTER + """
            ORDER BY a.submitted_at DESC, a.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AftersaleRow> findMerchantAftersales(
            @Param("merchantId") UUID merchantId,
            @Param("statuses") List<String> statuses,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select("""
            SELECT status, count(*) total FROM trade_aftersale
            WHERE merchant_id = #{merchantId}
            GROUP BY status
            """)
    List<StatusCountRow> countMerchantByStatus(@Param("merchantId") UUID merchantId);

    @Select("""
            <script>
            SELECT l.order_id, l.id, l.product_id, l.offer_id, l.product_name, l.sku_name, l.cover_url,
                   l.unit_amount_minor, l.quantity, l.subtotal_minor
            FROM trade_order_line l
            WHERE l.order_id IN
            <foreach collection="orderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY l.order_id, l.sort_order, l.id
            </script>
            """)
    List<LineRow> findLines(@Param("orderIds") List<UUID> orderIds);

    @Select("""
            <script>
            SELECT id, aftersale_id, slot
            FROM trade_aftersale_image
            WHERE aftersale_id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY aftersale_id, sort_order, id
            </script>
            """)
    List<ImageRefRow> findImages(@Param("ids") List<UUID> aftersaleIds);

    @Select("""
            SELECT action, from_status, to_status, occurred_at
            FROM trade_aftersale_event
            WHERE aftersale_id = #{id}
            ORDER BY occurred_at, created_at, id
            """)
    List<EventRow> findEvents(@Param("id") UUID aftersaleId);

    @Select("""
            SELECT r.status, r.amount_minor, r.succeeded_at
            FROM trade_refund r
            WHERE r.id = #{refundId}
            """)
    RefundStateRow findRefundState(@Param("refundId") UUID refundId);

    @Select("""
            SELECT p.transaction_id FROM trade_payment p
            WHERE p.order_id = #{orderId} AND p.status = 'SUCCEEDED'
            LIMIT 1
            """)
    String findTransactionId(@Param("orderId") UUID orderId);

    @Select("""
            SELECT o.order_no, o.merchant_id, o.merchant_name, o.aftersale_party
            FROM trade_order o
            WHERE o.id = #{orderId}
            """)
    OrderHeadRow findOrderHead(@Param("orderId") UUID orderId);

    // ---- 改状态 ----

    /** 按版本改：只有当前状态和版本都对上才改；返回改了几行。 */
    @Update("""
            UPDATE trade_aftersale
            SET status = #{toStatus},
                due_at = #{dueAt},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{id} AND status = #{fromStatus}
            """)
    int changeStatus(
            @Param("id") UUID id,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("dueAt") Instant dueAt,
            @Param("at") Instant at
    );

    @Update("""
            UPDATE trade_aftersale
            SET reviewed_at = #{at},
                return_contact = #{contact},
                return_phone = #{phone},
                return_address = #{address},
                return_note = #{note}
            WHERE id = #{id}
            """)
    int saveAgreement(
            @Param("id") UUID id,
            @Param("contact") String contact,
            @Param("phone") String phone,
            @Param("address") String address,
            @Param("note") String note,
            @Param("at") Instant at
    );

    @Update("""
            UPDATE trade_aftersale
            SET reject_reason = #{reason},
                merchant_note = #{note},
                reviewed_at = COALESCE(reviewed_at, #{at}),
                received_at = CASE WHEN #{atReceipt} THEN #{at} ELSE received_at END,
                closed_at = #{at}
            WHERE id = #{id}
            """)
    int saveRejection(
            @Param("id") UUID id,
            @Param("reason") String reason,
            @Param("note") String note,
            @Param("atReceipt") boolean atReceipt,
            @Param("at") Instant at
    );

    @Update("""
            UPDATE trade_aftersale
            SET return_carrier = #{carrier},
                return_tracking_no = #{trackingNo},
                return_shipped_at = COALESCE(return_shipped_at, #{at}),
                updated_at = #{at}
            WHERE id = #{id}
            """)
    int saveWaybill(
            @Param("id") UUID id,
            @Param("carrier") String carrier,
            @Param("trackingNo") String trackingNo,
            @Param("at") Instant at
    );

    @Update("UPDATE trade_aftersale SET closed_at = #{at} WHERE id = #{id}")
    int markClosed(@Param("id") UUID id, @Param("at") Instant at);

    @Update("""
            UPDATE trade_aftersale
            SET refund_id = #{refundId},
                refund_minor = #{refundMinor},
                merchant_note = COALESCE(#{note}, merchant_note),
                received_at = CASE WHEN #{received} THEN #{at} ELSE received_at END
            WHERE id = #{id}
            """)
    int saveRefund(
            @Param("id") UUID id,
            @Param("refundId") UUID refundId,
            @Param("refundMinor") long refundMinor,
            @Param("note") String note,
            @Param("received") boolean received,
            @Param("at") Instant at
    );

    @Update("UPDATE trade_aftersale SET refunded_at = #{at}, closed_at = #{at} WHERE id = #{id}")
    int markRefunded(@Param("id") UUID id, @Param("at") Instant at);

    @Select("""
            INSERT INTO trade_aftersale_event (aftersale_id, action, from_status, to_status, actor_id, occurred_at)
            VALUES (#{id}, #{action}, #{fromStatus}, #{toStatus}, #{actorId}, #{at})
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertEvent(
            @Param("id") UUID aftersaleId,
            @Param("action") String action,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("actorId") UUID actorId,
            @Param("at") Instant at
    );

    /** 到了处理时限的售后单，跳过别的事务正在处理的。 */
    @Select("""
            SELECT id, order_id, status
            FROM trade_aftersale
            WHERE due_at IS NOT NULL AND due_at <= #{now}
            ORDER BY due_at ASC, id ASC
            LIMIT #{limit}
            """)
    List<DueRow> findDue(@Param("now") Instant now, @Param("limit") int limit);

    // ---- 凭证图片 ----

    @Select("""
            INSERT INTO trade_aftersale_image (uploaded_by, slot, content_type, size_bytes, sha256, storage_key,
                                               created_at)
            VALUES (#{uploadedBy}, #{slot}, #{contentType}, #{sizeBytes}, #{sha256}, #{storageKey}, #{at})
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertImage(
            @Param("uploadedBy") UUID uploadedBy,
            @Param("slot") String slot,
            @Param("contentType") String contentType,
            @Param("sizeBytes") int sizeBytes,
            @Param("sha256") String sha256,
            @Param("storageKey") String storageKey,
            @Param("at") Instant at
    );

    @Select("""
            SELECT count(*) FROM trade_aftersale_image
            WHERE uploaded_by = #{uploadedBy} AND created_at >= #{since}
            """)
    int countRecentUploads(@Param("uploadedBy") UUID uploadedBy, @Param("since") Instant since);

    @Select("""
            <script>
            SELECT id, uploaded_by, aftersale_id, slot, content_type, storage_key
            FROM trade_aftersale_image
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            FOR UPDATE
            </script>
            """)
    List<ImageRow> lockImages(@Param("ids") List<UUID> imageIds);

    @Update("""
            UPDATE trade_aftersale_image
            SET aftersale_id = #{aftersaleId}, sort_order = #{sort}
            WHERE id = #{imageId} AND aftersale_id IS NULL
            """)
    int attachImage(@Param("imageId") UUID imageId, @Param("aftersaleId") UUID aftersaleId, @Param("sort") int sort);

    @Select("""
            SELECT id, uploaded_by, aftersale_id, slot, content_type, storage_key
            FROM trade_aftersale_image
            WHERE id = #{imageId}
            """)
    ImageRow findImage(@Param("imageId") UUID imageId);

    // ---- 默认寄回地址 ----

    @Select("""
            SELECT contact_name, phone, address, note, version, updated_at
            FROM trade_merchant_return_address
            WHERE organization_id = #{organizationId}
            """)
    ReturnAddressRow findReturnAddress(@Param("organizationId") UUID organizationId);

    @Select("""
            INSERT INTO trade_merchant_return_address (organization_id, contact_name, phone, address, note,
                                                       updated_by, created_at, updated_at)
            VALUES (#{organizationId}, #{contact}, #{phone}, #{address}, #{note}, #{operatorId}, #{at}, #{at})
            ON CONFLICT (organization_id) DO UPDATE
            SET contact_name = EXCLUDED.contact_name,
                phone = EXCLUDED.phone,
                address = EXCLUDED.address,
                note = EXCLUDED.note,
                updated_by = EXCLUDED.updated_by,
                version = trade_merchant_return_address.version + 1,
                updated_at = EXCLUDED.updated_at
            RETURNING contact_name, phone, address, note, version, updated_at
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    ReturnAddressRow saveReturnAddress(
            @Param("organizationId") UUID organizationId,
            @Param("contact") String contact,
            @Param("phone") String phone,
            @Param("address") String address,
            @Param("note") String note,
            @Param("operatorId") UUID operatorId,
            @Param("at") Instant at
    );

    record AftersaleRow(
            UUID id,
            String aftersaleNo,
            UUID orderId,
            String orderNo,
            UUID buyerId,
            UUID merchantId,
            String merchantName,
            String aftersaleParty,
            String kind,
            String reason,
            String description,
            long requestedMinor,
            Long refundMinor,
            String status,
            String returnContact,
            String returnPhone,
            String returnAddress,
            String returnNote,
            String returnCarrier,
            String returnTrackingNo,
            String rejectReason,
            String merchantNote,
            UUID refundId,
            Instant dueAt,
            Instant submittedAt,
            Instant reviewedAt,
            Instant returnShippedAt,
            Instant receivedAt,
            Instant refundedAt,
            Instant closedAt,
            UUID idempotencyKey,
            String requestHash,
            int version,
            long paidMinor
    ) {
    }

    record OrderHeadRow(String orderNo, UUID merchantId, String merchantName, String aftersaleParty) {
    }

    record OrderFactsRow(String source, Instant completedAt, Instant shippedAt, boolean noReasonReturn) {
    }

    record StatusCountRow(String status, long total) {
    }

    record LineRow(
            UUID orderId,
            UUID id,
            UUID productId,
            UUID offerId,
            String productName,
            String skuName,
            String coverUrl,
            long unitAmountMinor,
            int quantity,
            long subtotalMinor
    ) {
    }

    record ImageRefRow(UUID id, UUID aftersaleId, String slot) {
    }

    record ImageRow(UUID id, UUID uploadedBy, UUID aftersaleId, String slot, String contentType, String storageKey) {
    }

    record EventRow(String action, String fromStatus, String toStatus, Instant occurredAt) {
    }

    record RefundStateRow(String status, long amountMinor, Instant succeededAt) {
    }

    record DueRow(UUID id, UUID orderId, String status) {
    }

    record ReturnAddressRow(String contactName, String phone, String address, String note, int version,
                            Instant updatedAt) {
    }
}
