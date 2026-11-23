package com.lowaltitude.inspection.trade.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 订单的读写。全部用绑定参数；买家的读写都以 buyer_id 为条件，不是自己的订单查不到。
 * “当前时间”由调用方传入。
 */
public interface TradeOrderMapper {

    /** 同一个买家的提交排队：两个同时到的相同请求，后到的在锁后读到先到的结果。事务结束自动释放。 */
    @Select("""
            SELECT 1 FROM (
                SELECT pg_advisory_xact_lock(hashtextextended('trade-order:' || CAST(#{buyerId} AS text), 0))
            ) locked
            """)
    Integer lockBuyer(@Param("buyerId") UUID buyerId);

    @Select("""
            SELECT id, request_hash
            FROM trade_order
            WHERE buyer_id = #{buyerId}
              AND idempotency_key = #{idempotencyKey}
            """)
    KeyRow findByKey(@Param("buyerId") UUID buyerId, @Param("idempotencyKey") UUID idempotencyKey);

    // ---- 商家收款账户 ----

    @Select("""
            SELECT organization_id, sub_mchid, active
            FROM trade_merchant_account
            WHERE organization_id = #{organizationId}
            """)
    MerchantAccountRow findMerchantAccount(@Param("organizationId") UUID organizationId);

    @Select("""
            SELECT count(*)
            FROM identity_organization
            WHERE id = #{organizationId}
            """)
    int countOrganization(@Param("organizationId") UUID organizationId);

    @Select("""
            INSERT INTO trade_merchant_account (organization_id, sub_mchid, active, created_at, updated_at)
            VALUES (#{organizationId}, #{subMchid}, #{active}, #{at}, #{at})
            ON CONFLICT (organization_id) DO UPDATE
            SET sub_mchid = EXCLUDED.sub_mchid,
                active = EXCLUDED.active,
                version = trade_merchant_account.version + 1,
                updated_at = EXCLUDED.updated_at
            RETURNING organization_id, sub_mchid, active
            """)
    MerchantAccountRow saveMerchantAccount(
            @Param("organizationId") UUID organizationId,
            @Param("subMchid") String subMchid,
            @Param("active") boolean active,
            @Param("at") Instant at
    );

    // ---- 写订单 ----

    @Select("""
            INSERT INTO trade_order (
                order_no, buyer_id, merchant_id, merchant_kind, merchant_name, sub_mchid, source, city_code,
                delivery_mode, goods_minor, shipping_minor, payable_minor, buyer_note, pay_before,
                idempotency_key, request_hash, created_at, updated_at
            )
            VALUES (
                #{orderNo}, #{buyerId}, #{merchantId}, #{merchantKind}, #{merchantName}, #{subMchid}, 'MALL', #{cityCode},
                #{deliveryMode}, #{goodsMinor}, #{shippingMinor}, #{payableMinor}, #{buyerNote}, #{payBefore},
                #{idempotencyKey}, #{requestHash}, #{at}, #{at}
            )
            RETURNING id
            """)
    UUID insertOrder(
            @Param("orderNo") String orderNo,
            @Param("buyerId") UUID buyerId,
            @Param("merchantId") UUID merchantId,
            @Param("merchantKind") String merchantKind,
            @Param("merchantName") String merchantName,
            @Param("subMchid") String subMchid,
            @Param("cityCode") String cityCode,
            @Param("deliveryMode") String deliveryMode,
            @Param("goodsMinor") long goodsMinor,
            @Param("shippingMinor") long shippingMinor,
            @Param("payableMinor") long payableMinor,
            @Param("buyerNote") String buyerNote,
            @Param("payBefore") Instant payBefore,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("at") Instant at
    );

    @Select("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended('trade-order-no:' || #{orderNo}, 0))")
    Integer lockOrderNo(@Param("orderNo") String orderNo);

    @Select("""
            SELECT count(*)
            FROM trade_order
            WHERE order_no = #{orderNo}
            """)
    int countOrderNo(@Param("orderNo") String orderNo);

    @Insert("""
            INSERT INTO trade_order_line (
                order_id, product_id, offer_id, product_name, sku_name, cover_url,
                unit_amount_minor, quantity, subtotal_minor, sort_order, created_at
            )
            VALUES (
                #{orderId}, #{productId}, #{offerId}, #{productName}, #{skuName}, #{coverUrl},
                #{unitAmountMinor}, #{quantity}, #{subtotalMinor}, #{sortOrder}, #{at}
            )
            """)
    int insertLine(
            @Param("orderId") UUID orderId,
            @Param("productId") UUID productId,
            @Param("offerId") UUID offerId,
            @Param("productName") String productName,
            @Param("skuName") String skuName,
            @Param("coverUrl") String coverUrl,
            @Param("unitAmountMinor") long unitAmountMinor,
            @Param("quantity") int quantity,
            @Param("subtotalMinor") long subtotalMinor,
            @Param("sortOrder") int sortOrder,
            @Param("at") Instant at
    );

    @Insert("""
            INSERT INTO trade_order_delivery (
                order_id, mode, recipient_name, phone, province_name, city_name, address_detail,
                pickup_point, created_at, updated_at
            )
            VALUES (
                #{orderId}, #{mode}, #{recipientName}, #{phone}, #{provinceName}, #{cityName}, #{addressDetail},
                #{pickupPoint}, #{at}, #{at}
            )
            """)
    int insertDelivery(
            @Param("orderId") UUID orderId,
            @Param("mode") String mode,
            @Param("recipientName") String recipientName,
            @Param("phone") String phone,
            @Param("provinceName") String provinceName,
            @Param("cityName") String cityName,
            @Param("addressDetail") String addressDetail,
            @Param("pickupPoint") String pickupPoint,
            @Param("at") Instant at
    );

    @Insert("""
            INSERT INTO trade_state_transition (order_id, from_status, to_status, cause, actor_id, occurred_at)
            VALUES (#{orderId}, #{fromStatus}, #{toStatus}, #{cause}, #{actorId}, #{at})
            """)
    int insertTransition(
            @Param("orderId") UUID orderId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("cause") String cause,
            @Param("actorId") UUID actorId,
            @Param("at") Instant at
    );

    /** 未付款关闭：只改仍是待付款、版本也对得上的那一张。 */
    @Update("""
            UPDATE trade_order
            SET status = 'CLOSED',
                closed_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{orderId}
              AND status = 'PENDING_PAYMENT'
              AND version = #{version}
            """)
    int closeUnpaid(@Param("orderId") UUID orderId, @Param("version") int version, @Param("at") Instant at);

    /**
     * 已过付款截止时间、并且没有进行中支付单的待付款订单，跳过别的事务正在处理的行。
     * 有进行中支付单的要先向渠道查单，由支付服务处理。
     */
    @Select("""
            SELECT o.id, o.version
            FROM trade_order o
            WHERE o.status = 'PENDING_PAYMENT'
              AND o.pay_before <= #{now}
              AND NOT EXISTS (
                  SELECT 1 FROM trade_payment p
                  WHERE p.order_id = o.id AND p.status IN ('CREATED', 'PREPAID'))
            ORDER BY o.pay_before ASC, o.id ASC
            LIMIT #{limit}
            FOR UPDATE OF o SKIP LOCKED
            """)
    List<ExpiredRow> lockExpired(@Param("now") Instant now, @Param("limit") int limit);

    /** 这张订单有没有进行中（已建或已拿到预支付）的支付单。 */
    @Select("""
            SELECT count(*) FROM trade_payment
            WHERE order_id = #{orderId} AND status IN ('CREATED', 'PREPAID')
            """)
    int countOpenPayments(@Param("orderId") UUID orderId);

    // ---- 读订单 ----

    String ORDER_COLUMNS = """
            SELECT o.id,
                   o.order_no,
                   o.buyer_id,
                   o.merchant_id,
                   o.merchant_kind,
                   o.merchant_name,
                   o.source,
                   o.source_ref,
                   o.status,
                   o.delivery_mode,
                   o.goods_minor,
                   o.shipping_minor,
                   o.payable_minor,
                   o.paid_minor,
                   o.refunded_minor,
                   o.currency,
                   o.buyer_note,
                   o.pay_before,
                   o.paid_at,
                   o.shipped_at,
                   o.completed_at,
                   o.closed_at,
                   o.created_at,
                   o.version
            FROM trade_order o
            """;

    @Select(ORDER_COLUMNS + """
            WHERE o.id = #{orderId}
              AND o.buyer_id = #{buyerId}
            """)
    OrderRow findBuyerOrder(@Param("buyerId") UUID buyerId, @Param("orderId") UUID orderId);

    @Select(ORDER_COLUMNS + """
            WHERE o.id = #{orderId}
              AND o.buyer_id = #{buyerId}
            FOR UPDATE
            """)
    OrderRow lockBuyerOrder(@Param("buyerId") UUID buyerId, @Param("orderId") UUID orderId);

    @Select(ORDER_COLUMNS + """
            WHERE o.id = #{orderId}
            """)
    OrderRow findOrder(@Param("orderId") UUID orderId);

    /** 买家的“商品订单”只列商城订单：收费报名等业务来源的订单在各自的业务页面里看（E09）。 */
    String BUYER_FILTER = """
            WHERE o.buyer_id = #{buyerId}
              AND o.source = 'MALL'
            <if test="statuses != null and statuses.size() > 0">
              AND o.status IN
              <foreach collection="statuses" item="status" open="(" separator="," close=")">#{status}</foreach>
            </if>
            """;

    @Select("<script>SELECT count(*) FROM trade_order o " + BUYER_FILTER + "</script>")
    long countBuyerOrders(@Param("buyerId") UUID buyerId, @Param("statuses") List<String> statuses);

    @Select("<script>" + ORDER_COLUMNS + BUYER_FILTER + """
            ORDER BY o.created_at DESC, o.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<OrderRow> findBuyerOrders(
            @Param("buyerId") UUID buyerId,
            @Param("statuses") List<String> statuses,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select("""
            <script>
            SELECT id, order_id, product_id, offer_id, product_name, sku_name, cover_url,
                   unit_amount_minor, quantity, subtotal_minor
            FROM trade_order_line
            WHERE order_id IN
            <foreach collection="orderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY sort_order ASC, id ASC
            </script>
            """)
    List<LineRow> findLines(@Param("orderIds") List<UUID> orderIds);

    @Select("""
            <script>
            SELECT order_id, mode, recipient_name, phone, province_name, city_name, address_detail,
                   pickup_point, pickup_code, carrier_name, tracking_no
            FROM trade_order_delivery
            WHERE order_id IN
            <foreach collection="orderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<DeliveryRow> findDeliveries(@Param("orderIds") List<UUID> orderIds);

    // ---- 商家（E05）：只看、只改本机构卖出的商城订单 ----
    // 业务来源的订单（E09 收费报名）不给商家的列表、发货、核销、退款接口：钱只能由业务单的退改退回。

    @Select(ORDER_COLUMNS + """
            WHERE o.id = #{orderId}
              AND o.merchant_id = #{merchantId}
              AND o.source = 'MALL'
            """)
    OrderRow findMerchantOrder(@Param("merchantId") UUID merchantId, @Param("orderId") UUID orderId);

    @Select(ORDER_COLUMNS + """
            WHERE o.id = #{orderId}
              AND o.merchant_id = #{merchantId}
              AND o.source = 'MALL'
            FOR UPDATE
            """)
    OrderRow lockMerchantOrder(@Param("merchantId") UUID merchantId, @Param("orderId") UUID orderId);

    String MERCHANT_FILTER = """
            WHERE o.merchant_id = #{merchantId}
              AND o.source = 'MALL'
            <if test="status != null">
              AND o.status = #{status}
            </if>
            <if test="deliveryMode != null">
              AND o.delivery_mode = #{deliveryMode}
            </if>
            """;

    @Select("<script>SELECT count(*) FROM trade_order o " + MERCHANT_FILTER + "</script>")
    long countMerchantOrders(@Param("merchantId") UUID merchantId, @Param("status") String status,
                             @Param("deliveryMode") String deliveryMode);

    @Select("<script>" + ORDER_COLUMNS + MERCHANT_FILTER + """
            ORDER BY o.created_at DESC, o.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<OrderRow> findMerchantOrders(
            @Param("merchantId") UUID merchantId,
            @Param("status") String status,
            @Param("deliveryMode") String deliveryMode,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /** 发货：已付款的快递单改成已发货。 */
    @Update("""
            UPDATE trade_order
            SET status = 'SHIPPED',
                shipped_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{orderId}
              AND status = 'PAID'
              AND delivery_mode = 'EXPRESS'
              AND version = #{version}
            """)
    int markShipped(@Param("orderId") UUID orderId, @Param("version") int version, @Param("at") Instant at);

    @Update("""
            UPDATE trade_order_delivery
            SET carrier_name = #{carrierName},
                tracking_no = #{trackingNo},
                version = version + 1,
                updated_at = #{at}
            WHERE order_id = #{orderId}
            """)
    int saveTracking(
            @Param("orderId") UUID orderId,
            @Param("carrierName") String carrierName,
            @Param("trackingNo") String trackingNo,
            @Param("at") Instant at
    );

    /** 完成：已发货的确认收货（买家或到期自动），已付款的自提单核销取货码。 */
    @Update("""
            UPDATE trade_order
            SET status = 'COMPLETED',
                completed_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{orderId}
              AND status = #{fromStatus}
              AND version = #{version}
            """)
    int markCompleted(
            @Param("orderId") UUID orderId,
            @Param("fromStatus") String fromStatus,
            @Param("version") int version,
            @Param("at") Instant at
    );

    @Select("""
            SELECT pickup_code, pickup_failures FROM trade_order_delivery
            WHERE order_id = #{orderId}
            FOR UPDATE
            """)
    PickupRow lockPickup(@Param("orderId") UUID orderId);

    /** 取货码输错一次；到 10 次后不再加（表上的约束兜底）。 */
    @Update("""
            UPDATE trade_order_delivery
            SET pickup_failures = pickup_failures + 1,
                updated_at = #{at}
            WHERE order_id = #{orderId}
              AND pickup_failures < 10
            """)
    int recordPickupFailure(@Param("orderId") UUID orderId, @Param("at") Instant at);

    /** 发货满 10 天还没确认收货的，跳过别的事务正在处理的行。 */
    @Select("""
            SELECT id, version
            FROM trade_order
            WHERE status = 'SHIPPED'
              AND shipped_at <= #{shippedBefore}
            ORDER BY shipped_at ASC, id ASC
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<ExpiredRow> lockReceiptDue(@Param("shippedBefore") Instant shippedBefore, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT id, order_id, amount_minor, reason, status, created_at, succeeded_at
            FROM trade_refund
            WHERE order_id IN
            <foreach collection="orderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY created_at ASC, id ASC
            </script>
            """)
    List<RefundRow> findRefunds(@Param("orderIds") List<UUID> orderIds);

    /**
     * 这些订单最后一次进入“已关闭”的原因（BUYER_CANCEL 或 TIMEOUT）。关单后才到的付款会把订单重新打开成已付款，
     * 所以只看最后一次；同一时刻有两条时按写入时间、再按 id 取一条，结果稳定。
     */
    @Select("""
            <script>
            SELECT DISTINCT ON (order_id) order_id, cause
            FROM trade_state_transition
            WHERE to_status = 'CLOSED'
              AND order_id IN
            <foreach collection="orderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY order_id, occurred_at DESC, created_at DESC, id DESC
            </script>
            """)
    List<CloseCauseRow> findCloseCauses(@Param("orderIds") List<UUID> orderIds);

    // ---- 0.6.0：交易主体快照、报名参与人、渠道交易号、售后入口 ----

    /** 下单后写上交易主体快照（发货仓、开票方、售后责任方）。 */
    @Update("""
            UPDATE trade_order
            SET fulfilled_from = #{fulfilledFrom},
                invoice_issuer = #{invoiceIssuer},
                aftersale_party = #{aftersaleParty}
            WHERE id = #{orderId}
            """)
    int saveParties(
            @Param("orderId") UUID orderId,
            @Param("fulfilledFrom") String fulfilledFrom,
            @Param("invoiceIssuer") String invoiceIssuer,
            @Param("aftersaleParty") String aftersaleParty
    );

    /** 收费报名订单的参与人姓名（显示用）。 */
    @Update("""
            UPDATE trade_order
            SET participant_names = #{participantNames}
            WHERE id = #{orderId}
            """)
    int saveParticipantNames(@Param("orderId") UUID orderId, @Param("participantNames") String participantNames);

    /** 这张单上不支持七天无理由的商品行（按供给）。 */
    @Update("""
            <script>
            UPDATE trade_order_line
            SET no_reason_return = false
            WHERE order_id = #{orderId}
              AND offer_id IN
            <foreach collection="offerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int markLinesWithoutNoReasonReturn(@Param("orderId") UUID orderId, @Param("offerIds") List<UUID> offerIds);

    /**
     * 订单的附加信息：交易主体快照、参与人、付款成功的微信交易号、这张单是否全部支持七天无理由，
     * 以及售后（最近一张、有没有没结束的、一共几张）。
     */
    @Select("""
            <script>
            SELECT o.id order_id,
                   o.fulfilled_from,
                   o.invoice_issuer,
                   o.aftersale_party,
                   o.participant_names,
                   (SELECT p.transaction_id FROM trade_payment p
                    WHERE p.order_id = o.id AND p.status = 'SUCCEEDED' LIMIT 1) transaction_id,
                   NOT EXISTS (SELECT 1 FROM trade_order_line l
                               WHERE l.order_id = o.id AND NOT l.no_reason_return) no_reason_return,
                   latest.id latest_aftersale_id,
                   latest.status latest_aftersale_status,
                   (SELECT count(*) FROM trade_aftersale a WHERE a.order_id = o.id) aftersale_count,
                   EXISTS (SELECT 1 FROM trade_aftersale a
                           WHERE a.order_id = o.id
                             AND a.status IN ('PENDING_REVIEW', 'AWAITING_RETURN', 'RETURN_SHIPPED', 'REFUNDING'))
                       aftersale_open,
                   (SELECT COALESCE(sum(r.amount_minor), 0) FROM trade_refund r
                    WHERE r.order_id = o.id AND r.status IN ('REQUESTED', 'PROCESSING', 'ABNORMAL')) reserved_minor
            FROM trade_order o
            LEFT JOIN LATERAL (
                SELECT a.id, a.status FROM trade_aftersale a
                WHERE a.order_id = o.id
                ORDER BY a.submitted_at DESC, a.id DESC
                LIMIT 1
            ) latest ON true
            WHERE o.id IN
            <foreach collection="orderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<OrderExtrasRow> findOrderExtras(@Param("orderIds") List<UUID> orderIds);

    record OrderExtrasRow(
            UUID orderId,
            String fulfilledFrom,
            String invoiceIssuer,
            String aftersaleParty,
            String participantNames,
            String transactionId,
            boolean noReasonReturn,
            UUID latestAftersaleId,
            String latestAftersaleStatus,
            long aftersaleCount,
            boolean aftersaleOpen,
            long reservedMinor
    ) {
    }

    record KeyRow(UUID id, String requestHash) {
    }

    record MerchantAccountRow(UUID organizationId, String subMchid, boolean active) {
    }

    record ExpiredRow(UUID id, int version) {
    }

    record OrderRow(
            UUID id,
            String orderNo,
            UUID buyerId,
            UUID merchantId,
            String merchantKind,
            String merchantName,
            String source,
            /** 业务单 ID：商城订单为空。 */
            UUID sourceRef,
            String status,
            String deliveryMode,
            long goodsMinor,
            long shippingMinor,
            long payableMinor,
            long paidMinor,
            long refundedMinor,
            String currency,
            String buyerNote,
            Instant payBefore,
            Instant paidAt,
            Instant shippedAt,
            Instant completedAt,
            Instant closedAt,
            Instant createdAt,
            int version
    ) {
    }

    record LineRow(
            UUID id,
            UUID orderId,
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

    record DeliveryRow(
            UUID orderId,
            String mode,
            String recipientName,
            String phone,
            String provinceName,
            String cityName,
            String addressDetail,
            String pickupPoint,
            String pickupCode,
            String carrierName,
            String trackingNo
    ) {
    }

    record PickupRow(String pickupCode, int pickupFailures) {
    }

    record CloseCauseRow(UUID orderId, String cause) {
    }

    record RefundRow(
            UUID id,
            UUID orderId,
            long amountMinor,
            String reason,
            String status,
            Instant createdAt,
            Instant succeededAt
    ) {
    }
}
