package com.lowaltitude.inspection.trade.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 运营端“订单查询”（契约 contracts/openapi-ops-lookup.yaml）的只读查询。买家显示名和手机号直接读 identity_user
 * （和收款账户读 identity_organization 一样），手机号只用于精确查找和在服务里打码，不原样给出。
 */
public interface TradeOpsLookupMapper {

    /**
     * 筛选：phone 按买家登录手机号；orderId 按订单 ID；number 依次对订单号、商户订单号、微信交易号、退款单号、售后单号
     * 精确匹配（任一命中）。日期是北京时间的 [from, to)。
     */
    String FILTER = """
            FROM trade_order o
            JOIN identity_user u ON u.id = o.buyer_id
            WHERE 1 = 1
            <if test="phone != null">AND u.mobile = #{phone}</if>
            <if test="orderId != null">AND o.id = #{orderId}</if>
            <if test="number != null">
              AND (o.order_no = #{number}
                   OR EXISTS (SELECT 1 FROM trade_payment p WHERE p.order_id = o.id
                              AND (p.out_trade_no = #{number} OR p.transaction_id = #{number}))
                   OR EXISTS (SELECT 1 FROM trade_refund r WHERE r.order_id = o.id
                              AND (r.out_refund_no = #{number} OR r.refund_id = #{number}))
                   OR EXISTS (SELECT 1 FROM trade_aftersale a WHERE a.order_id = o.id
                              AND a.aftersale_no = #{number}))
            </if>
            <if test="buyerId != null">AND o.buyer_id = #{buyerId}</if>
            <if test="source != null">AND o.source = #{source}</if>
            <if test="status != null">AND o.status = #{status}</if>
            <if test="from != null">AND o.created_at &gt;= #{from}</if>
            <if test="to != null">AND o.created_at &lt; #{to}</if>
            """;

    String SUMMARY_COLUMNS = """
            SELECT o.id, o.order_no, o.source, o.status, o.city_code, o.merchant_name,
                   o.buyer_id, CASE WHEN u.status = 'CLOSED' THEN NULL ELSE u.display_name END buyer_name,
                   CASE WHEN u.status = 'CLOSED' THEN NULL ELSE u.mobile END buyer_phone,
                   o.payer_name,
                   COALESCE((SELECT l.product_name FROM trade_order_line l WHERE l.order_id = o.id
                             ORDER BY l.sort_order, l.id LIMIT 1), '') title,
                   (SELECT count(*) FROM trade_order_line l WHERE l.order_id = o.id) item_count,
                   o.payable_minor, o.paid_minor, o.refunded_minor, o.created_at, o.paid_at
            """;

    @Select("<script>SELECT count(*)\n" + FILTER + "</script>")
    long countOrders(@Param("phone") String phone, @Param("orderId") UUID orderId, @Param("number") String number,
                     @Param("buyerId") UUID buyerId, @Param("source") String source, @Param("status") String status,
                     @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    @Select("<script>" + SUMMARY_COLUMNS + FILTER + """
            ORDER BY o.created_at DESC, o.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """ + "</script>")
    List<OrderSummaryRow> findOrders(@Param("phone") String phone, @Param("orderId") UUID orderId,
                                     @Param("number") String number, @Param("buyerId") UUID buyerId,
                                     @Param("source") String source, @Param("status") String status,
                                     @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                                     @Param("limit") int limit, @Param("offset") int offset);

    @Select(SUMMARY_COLUMNS + """
            FROM trade_order o
            JOIN identity_user u ON u.id = o.buyer_id
            WHERE o.id = #{orderId}
            """)
    OrderSummaryRow findOrder(@Param("orderId") UUID orderId);

    @Select("""
            SELECT source_ref, delivery_mode, goods_minor, shipping_minor, buyer_note, pay_before, shipped_at,
                   completed_at, closed_at
            FROM trade_order WHERE id = #{orderId}
            """)
    OrderExtraRow findOrderExtra(@Param("orderId") UUID orderId);

    @Select("""
            SELECT product_name, sku_name, unit_amount_minor, quantity, subtotal_minor
            FROM trade_order_line WHERE order_id = #{orderId}
            ORDER BY sort_order, id
            """)
    List<LineRow> findLines(@Param("orderId") UUID orderId);

    @Select("""
            SELECT mode, recipient_name, phone, city_name, pickup_point, carrier_name, tracking_no
            FROM trade_order_delivery WHERE order_id = #{orderId}
            """)
    DeliveryRow findDelivery(@Param("orderId") UUID orderId);

    @Select("""
            SELECT out_trade_no, transaction_id, amount_minor, status, created_at, succeeded_at, closed_at
            FROM trade_payment WHERE order_id = #{orderId}
            ORDER BY created_at, id
            """)
    List<PaymentRow> findPayments(@Param("orderId") UUID orderId);

    @Select("""
            SELECT out_refund_no, refund_id, amount_minor, reason, initiator, status, created_at, accepted_at,
                   succeeded_at
            FROM trade_refund WHERE order_id = #{orderId}
            ORDER BY created_at, id
            """)
    List<RefundRow> findRefunds(@Param("orderId") UUID orderId);

    @Select("""
            SELECT id, aftersale_no, kind, status, reason, requested_minor, refund_minor, submitted_at
            FROM trade_aftersale WHERE order_id = #{orderId}
            ORDER BY created_at, id
            """)
    List<AftersaleRow> findAftersales(@Param("orderId") UUID orderId);

    @Select("""
            SELECT from_status, to_status, cause, occurred_at
            FROM trade_state_transition WHERE order_id = #{orderId}
            ORDER BY occurred_at, created_at, id
            """)
    List<TransitionRow> findTransitions(@Param("orderId") UUID orderId);

    @Select("SELECT count(*) FROM trade_order WHERE buyer_id = #{buyerId}")
    long countByBuyer(@Param("buyerId") UUID buyerId);

    record OrderSummaryRow(UUID id, String orderNo, String source, String status, String cityCode,
                           String merchantName, UUID buyerId, String buyerName, String buyerPhone, String payerName,
                           String title, long itemCount, long payableMinor, long paidMinor, long refundedMinor,
                           OffsetDateTime createdAt, OffsetDateTime paidAt) {
    }

    record OrderExtraRow(UUID sourceRef, String deliveryMode, long goodsMinor, long shippingMinor, String buyerNote,
                         OffsetDateTime payBefore, OffsetDateTime shippedAt, OffsetDateTime completedAt,
                         OffsetDateTime closedAt) {
    }

    record LineRow(String productName, String skuName, long unitAmountMinor, int quantity, long subtotalMinor) {
    }

    record DeliveryRow(String mode, String recipientName, String phone, String cityName, String pickupPoint,
                       String carrierName, String trackingNo) {
    }

    record PaymentRow(String outTradeNo, String transactionId, long amountMinor, String status,
                      OffsetDateTime createdAt, OffsetDateTime succeededAt, OffsetDateTime closedAt) {
    }

    record RefundRow(String outRefundNo, String refundId, long amountMinor, String reason, String initiator,
                     String status, OffsetDateTime createdAt, OffsetDateTime acceptedAt,
                     OffsetDateTime succeededAt) {
    }

    record AftersaleRow(UUID id, String aftersaleNo, String kind, String status, String reason, long requestedMinor,
                        Long refundMinor, OffsetDateTime submittedAt) {
    }

    record TransitionRow(String fromStatus, String toStatus, String cause, OffsetDateTime occurredAt) {
    }
}
