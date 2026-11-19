package com.lowaltitude.inspection.trade.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 支付单和渠道回调的读写。买家入口都以 buyer_id 为条件；按商户单号入账的入口只给回调和查单用。
 * “当前时间”由调用方传入。
 */
public interface TradePaymentMapper {

    String PAYMENT_ORDER_COLUMNS = """
            SELECT o.id, o.buyer_id, o.status, o.sub_mchid, o.merchant_name, o.payable_minor, o.pay_before, o.version
            FROM trade_order o
            """;

    String PAYMENT_COLUMNS = """
            SELECT p.id, p.order_id, p.out_trade_no, p.sub_mchid, p.amount_minor, p.status, p.prepay_id,
                   p.prepay_expires_at, p.transaction_id, p.idempotency_key
            FROM trade_payment p
            """;

    // ---- 订单 ----

    @Select(PAYMENT_ORDER_COLUMNS + """
            WHERE o.id = #{orderId} AND o.buyer_id = #{buyerId}
            FOR UPDATE
            """)
    PaymentOrderRow lockBuyerOrder(@Param("buyerId") UUID buyerId, @Param("orderId") UUID orderId);

    @Select(PAYMENT_ORDER_COLUMNS + """
            WHERE o.id = #{orderId} AND o.buyer_id = #{buyerId}
            """)
    PaymentOrderRow findBuyerOrder(@Param("buyerId") UUID buyerId, @Param("orderId") UUID orderId);

    @Select(PAYMENT_ORDER_COLUMNS + """
            WHERE o.id = #{orderId}
            FOR UPDATE
            """)
    PaymentOrderRow lockOrder(@Param("orderId") UUID orderId);

    /**
     * 付款成功：待付款或（极少见的）已关闭的订单改成已付款，实付记为应付。
     * 已关闭的单是“关单后才收到付款”，钱以渠道为准，重新打开成已付款，由商家发货或退款。
     */
    @Update("""
            UPDATE trade_order
            SET status = 'PAID',
                paid_minor = payable_minor,
                paid_at = #{paidAt},
                closed_at = NULL,
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{orderId}
              AND status IN ('PENDING_PAYMENT', 'CLOSED')
            """)
    int markOrderPaid(@Param("orderId") UUID orderId, @Param("paidAt") Instant paidAt, @Param("at") Instant at);

    /** 自提单付款后生成 6 位取货码（只生成一次；关单后才到的付款重新打开时也一样）。 */
    @Update("""
            UPDATE trade_order_delivery
            SET pickup_code = #{pickupCode},
                version = version + 1,
                updated_at = #{at}
            WHERE order_id = #{orderId}
              AND mode = 'PICKUP'
              AND pickup_code IS NULL
            """)
    int assignPickupCode(@Param("orderId") UUID orderId, @Param("pickupCode") String pickupCode, @Param("at") Instant at);

    // ---- 支付单 ----

    @Select(PAYMENT_COLUMNS + """
            WHERE p.order_id = #{orderId} AND p.status IN ('CREATED', 'PREPAID')
            FOR UPDATE
            """)
    PaymentRow lockOpenPayment(@Param("orderId") UUID orderId);

    @Select(PAYMENT_COLUMNS + """
            WHERE p.order_id = #{orderId} AND p.status IN ('CREATED', 'PREPAID')
            """)
    PaymentRow findOpenPayment(@Param("orderId") UUID orderId);

    @Select(PAYMENT_COLUMNS + """
            WHERE p.order_id = #{orderId} AND p.idempotency_key = #{idempotencyKey}
            """)
    PaymentRow findPaymentByKey(@Param("orderId") UUID orderId, @Param("idempotencyKey") UUID idempotencyKey);

    @Select(PAYMENT_COLUMNS + """
            WHERE p.id = #{paymentId}
            FOR UPDATE
            """)
    PaymentRow lockPayment(@Param("paymentId") UUID paymentId);

    @Select(PAYMENT_COLUMNS + """
            WHERE p.out_trade_no = #{outTradeNo}
            """)
    PaymentRow findPaymentByOutTradeNo(@Param("outTradeNo") String outTradeNo);

    @Select("SELECT count(*) FROM trade_payment WHERE out_trade_no = #{outTradeNo}")
    int countOutTradeNo(@Param("outTradeNo") String outTradeNo);

    /**
     * 一张订单同时只能有一张进行中的支付单（部分唯一索引 uq_trade_payment_open 兜底）。
     * 用 @Select 跑 INSERT … RETURNING 时 MyBatis 不会自己清会话缓存，所以显式清掉，免得同一事务里之后读到旧结果。
     */
    @Select("""
            INSERT INTO trade_payment (order_id, channel, out_trade_no, sub_mchid, amount_minor, idempotency_key,
                                       created_at, updated_at)
            VALUES (#{orderId}, 'WECHAT_APP', #{outTradeNo}, #{subMchid}, #{amountMinor}, #{idempotencyKey}, #{at}, #{at})
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertPayment(
            @Param("orderId") UUID orderId,
            @Param("outTradeNo") String outTradeNo,
            @Param("subMchid") String subMchid,
            @Param("amountMinor") long amountMinor,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("at") Instant at
    );

    @Update("""
            UPDATE trade_payment
            SET status = 'PREPAID',
                prepay_id = #{prepayId},
                prepay_expires_at = #{expiresAt},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{paymentId} AND status = 'CREATED'
            """)
    int markPrepaid(
            @Param("paymentId") UUID paymentId,
            @Param("prepayId") String prepayId,
            @Param("expiresAt") Instant expiresAt,
            @Param("at") Instant at
    );

    /** 渠道确认付款成功。已关闭的支付单也可以（关单前一刻付了款），金额不符的不行。 */
    @Update("""
            UPDATE trade_payment
            SET status = 'SUCCEEDED',
                transaction_id = #{transactionId},
                succeeded_at = #{succeededAt},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{paymentId} AND status IN ('CREATED', 'PREPAID', 'CLOSED')
            """)
    int markSucceeded(
            @Param("paymentId") UUID paymentId,
            @Param("transactionId") String transactionId,
            @Param("succeededAt") Instant succeededAt,
            @Param("at") Instant at
    );

    /** 渠道的结果和这张支付单对不上（商户、金额），或者订单已经由另一张支付单付过：停下等人工处理。 */
    @Update("""
            UPDATE trade_payment
            SET status = 'MISMATCH',
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{paymentId} AND status <> 'SUCCEEDED'
            """)
    int markMismatch(@Param("paymentId") UUID paymentId, @Param("at") Instant at);

    @Update("""
            UPDATE trade_payment
            SET status = 'CLOSED',
                closed_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{paymentId} AND status IN ('CREATED', 'PREPAID')
            """)
    int closePayment(@Param("paymentId") UUID paymentId, @Param("at") Instant at);

    // ---- 定时兜底 ----

    /**
     * 建了有一会儿、订单还在付款期内、还没有结果的支付单：没收到回调时靠查单补上。
     * 按支付单上次更新的时间排：查不通的会被 touch 到后面，不会一直占着一批的名额。
     */
    @Select("""
            SELECT p.id, p.order_id, p.out_trade_no, p.sub_mchid, p.amount_minor, p.status, p.prepay_id,
                   p.prepay_expires_at, p.transaction_id, p.idempotency_key
            FROM trade_payment p
            JOIN trade_order o ON o.id = p.order_id
            WHERE p.status IN ('CREATED', 'PREPAID')
              AND o.status = 'PENDING_PAYMENT'
              AND p.created_at <= #{createdBefore}
              AND o.pay_before > #{now}
            ORDER BY p.updated_at ASC, p.id ASC
            LIMIT #{limit}
            """)
    List<PaymentRow> findPaymentsToPoll(
            @Param("createdBefore") Instant createdBefore,
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    /**
     * 已过付款截止时间的待付款订单上进行中的支付单：要先查单再关。
     * 和轮询一样按支付单上次更新的时间排，处理失败的被 touch 到后面。
     */
    @Select("""
            SELECT p.id, p.order_id, p.out_trade_no, p.sub_mchid, p.amount_minor, p.status, p.prepay_id,
                   p.prepay_expires_at, p.transaction_id, p.idempotency_key
            FROM trade_payment p
            JOIN trade_order o ON o.id = p.order_id
            WHERE p.status IN ('CREATED', 'PREPAID')
              AND o.status = 'PENDING_PAYMENT'
              AND o.pay_before <= #{now}
            ORDER BY p.updated_at ASC, p.id ASC
            LIMIT #{limit}
            """)
    List<PaymentRow> findExpiredWithOpenPayment(@Param("now") Instant now, @Param("limit") int limit);

    /** 这次没处理成（查不通、状态不明）：只更新时间，让它排到下一批的后面。 */
    @Update("UPDATE trade_payment SET updated_at = #{at} WHERE id = #{paymentId}")
    int touchPayment(@Param("paymentId") UUID paymentId, @Param("at") Instant at);

    // ---- 渠道回调 ----

    /** 记下收到的回调；同一渠道、同一通知 ID 第二次到达时不插入，返回 null。只存摘要，不存解密后的内容。 */
    @Select("""
            INSERT INTO trade_channel_notification (channel, notification_id, event_type, signature_valid,
                                                    out_trade_no, out_refund_no, outcome, received_at)
            VALUES ('WECHAT', #{notificationId}, #{eventType}, true, #{outTradeNo}, #{outRefundNo}, 'RECEIVED', #{at})
            ON CONFLICT (channel, notification_id) DO NOTHING
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertNotification(
            @Param("notificationId") String notificationId,
            @Param("eventType") String eventType,
            @Param("outTradeNo") String outTradeNo,
            @Param("outRefundNo") String outRefundNo,
            @Param("at") Instant at
    );

    @Select("""
            SELECT id, outcome FROM trade_channel_notification
            WHERE channel = 'WECHAT' AND notification_id = #{notificationId}
            """)
    NotificationRow findNotification(@Param("notificationId") String notificationId);

    /** 只写第一次处理的结果：同一条通知并发到达或失败后重试时，后来的不会把“已入账”改成“已处理过”。 */
    @Update("""
            UPDATE trade_channel_notification
            SET outcome = #{outcome}, detail = #{detail}
            WHERE id = #{id} AND outcome = 'RECEIVED'
            """)
    int finishNotification(@Param("id") UUID id, @Param("outcome") String outcome, @Param("detail") String detail);

    // ---- 行 ----

    record PaymentOrderRow(
            UUID id,
            UUID buyerId,
            String status,
            String subMchid,
            String merchantName,
            long payableMinor,
            Instant payBefore,
            int version
    ) {
    }

    record PaymentRow(
            UUID id,
            UUID orderId,
            String outTradeNo,
            String subMchid,
            long amountMinor,
            String status,
            String prepayId,
            Instant prepayExpiresAt,
            String transactionId,
            UUID idempotencyKey
    ) {
    }

    record NotificationRow(UUID id, String outcome) {
    }
}
