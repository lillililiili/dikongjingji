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

    // ---- 付款主体、企业订单、注销（S5-7、S5-8，迁移 V010） ----

    /** 刚建的订单记下付款主体快照（同一事务里，建单之后）。 */
    @Update("""
            UPDATE trade_order
               SET payer_type = #{payerType}, payer_organization_id = #{organizationId}, payer_name = #{payerName}
             WHERE id = #{orderId}
            """)
    int updatePayer(@Param("orderId") UUID orderId, @Param("payerType") String payerType,
                    @Param("organizationId") UUID organizationId, @Param("payerName") String payerName);

    @Select("""
            SELECT o.id, o.order_no, o.source, o.status, o.payable_minor, o.buyer_id, o.created_at,
                   (SELECT l.product_name FROM trade_order_line l WHERE l.order_id = o.id
                     ORDER BY l.sort_order, l.id LIMIT 1) AS first_line,
                   (SELECT count(*) FROM trade_order_line l WHERE l.order_id = o.id) AS line_count
              FROM trade_order o
             WHERE o.payer_organization_id = #{organizationId}
             ORDER BY o.created_at DESC, o.id DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<EnterpriseOrderRow> findEnterpriseOrders(@Param("organizationId") UUID organizationId,
                                                  @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE status = 'PENDING_PAYMENT') AS pending_payment,
                   count(*) FILTER (WHERE status IN ('PAID', 'SHIPPED', 'REFUNDING', 'REFUND_FAILED')) AS in_progress,
                   count(*) FILTER (WHERE status IN ('COMPLETED', 'REFUNDED')) AS completed
              FROM trade_order
             WHERE payer_organization_id = #{organizationId}
            """)
    EnterpriseCountRow countEnterpriseOrders(@Param("organizationId") UUID organizationId);

    /** 注销条件“没有进行中的订单”：待付款、已付款待发货、已发货、退款中（含退款异常）。 */
    @Select("""
            SELECT count(*)
              FROM trade_order
             WHERE buyer_id = #{buyerId}
               AND status IN ('PENDING_PAYMENT', 'PAID', 'SHIPPED', 'REFUNDING', 'REFUND_FAILED')
            """)
    long countOpenOrders(@Param("buyerId") UUID buyerId);

    /** 注销到期去标识：这个买家订单的配送快照里收货人和电话改成占位，地址清掉；订单和金额保留。 */
    @Update("""
            UPDATE trade_order_delivery d
               SET recipient_name = CASE WHEN d.recipient_name IS NULL THEN NULL ELSE '已注销用户' END,
                   phone = CASE WHEN d.phone IS NULL THEN NULL ELSE '13000000000' END,
                   address_detail = CASE WHEN d.address_detail IS NULL THEN NULL ELSE '已删除' END
              FROM trade_order o
             WHERE o.id = d.order_id
               AND o.buyer_id = #{buyerId}
            """)
    int anonymizeBuyerDeliveries(@Param("buyerId") UUID buyerId);

    record EnterpriseOrderRow(UUID id, String orderNo, String source, String status, long payableMinor,
                              UUID buyerId, Instant createdAt, String firstLine, long lineCount) {
    }

    record EnterpriseCountRow(long total, long pendingPayment, long inProgress, long completed) {
    }

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
                   o.version,
                   o.payer_type,
                   o.payer_name
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
            int version,
            /** 0.6.0 付款主体快照：PERSONAL / ENTERPRISE；0.6.0 之前的订单为空，当作个人。 */
            String payerType,
            String payerName
    ) {

        /** 0.6.0 之前的写法（测试里还在用）：没有付款主体快照。 */
        public OrderRow(UUID id, String orderNo, UUID buyerId, UUID merchantId, String merchantKind, String merchantName,
                 String source, UUID sourceRef, String status, String deliveryMode, long goodsMinor,
                 long shippingMinor, long payableMinor, long paidMinor, long refundedMinor, String currency,
                 String buyerNote, Instant payBefore, Instant paidAt, Instant shippedAt, Instant completedAt,
                 Instant closedAt, Instant createdAt, int version) {
            this(id, orderNo, buyerId, merchantId, merchantKind, merchantName, source, sourceRef, status,
                    deliveryMode, goodsMinor, shippingMinor, payableMinor, paidMinor, refundedMinor, currency,
                    buyerNote, payBefore, paidAt, shippedAt, completedAt, closedAt, createdAt, version, null, null);
        }
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
