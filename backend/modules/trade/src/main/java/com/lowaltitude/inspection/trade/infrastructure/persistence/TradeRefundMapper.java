package com.lowaltitude.inspection.trade.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 退款的读写（E05）。买家入口以 buyer_id、商家入口以 merchant_id 为条件；按退款单号的入口只给回调和查询用。
 * 锁的顺序和支付一样：先订单、后退款单。“当前时间”由调用方传入。
 */
public interface TradeRefundMapper {

    String REFUND_ORDER_COLUMNS = """
            SELECT o.id, o.buyer_id, o.merchant_id, o.status, o.sub_mchid, o.paid_minor, o.refunded_minor,
                   o.paid_at, o.version
            FROM trade_order o
            """;

    String REFUND_COLUMNS = """
            SELECT r.id, r.order_id, r.payment_id, r.out_refund_no, r.amount_minor, r.reason, r.initiator,
                   r.status, r.refund_id, r.idempotency_key, r.created_at, r.succeeded_at
            FROM trade_refund r
            """;

    // ---- 订单 ----

    @Select(REFUND_ORDER_COLUMNS + """
            WHERE o.id = #{orderId} AND o.buyer_id = #{buyerId}
            FOR UPDATE
            """)
    RefundOrderRow lockBuyerOrder(@Param("buyerId") UUID buyerId, @Param("orderId") UUID orderId);

    @Select(REFUND_ORDER_COLUMNS + """
            WHERE o.id = #{orderId} AND o.merchant_id = #{merchantId}
            FOR UPDATE
            """)
    RefundOrderRow lockMerchantOrder(@Param("merchantId") UUID merchantId, @Param("orderId") UUID orderId);

    @Select(REFUND_ORDER_COLUMNS + """
            WHERE o.id = #{orderId}
            FOR UPDATE
            """)
    RefundOrderRow lockOrder(@Param("orderId") UUID orderId);

    /** 全额退款开始：已付款、已发货、已完成或上次退款失败的订单 → 退款中。 */
    @Update("""
            UPDATE trade_order
            SET status = 'REFUNDING',
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{orderId}
              AND status IN ('PAID', 'SHIPPED', 'COMPLETED', 'REFUND_FAILED')
            """)
    int markOrderRefunding(@Param("orderId") UUID orderId, @Param("at") Instant at);

    /** 一笔退款到账：累加已退金额（表上的约束保证不超过实付），状态由调用方按规则给出。 */
    @Update("""
            UPDATE trade_order
            SET refunded_minor = refunded_minor + #{amountMinor},
                status = #{status},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{orderId}
            """)
    int addRefunded(
            @Param("orderId") UUID orderId,
            @Param("amountMinor") long amountMinor,
            @Param("status") String status,
            @Param("at") Instant at
    );

    @Update("""
            UPDATE trade_order
            SET status = #{status},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{orderId}
              AND status = #{fromStatus}
            """)
    int changeOrderStatus(
            @Param("orderId") UUID orderId,
            @Param("fromStatus") String fromStatus,
            @Param("status") String status,
            @Param("at") Instant at
    );

    // ---- 支付单 ----

    @Select("""
            SELECT id, out_trade_no, sub_mchid, amount_minor
            FROM trade_payment
            WHERE order_id = #{orderId} AND status = 'SUCCEEDED'
            """)
    PaidPaymentRow findSucceededPayment(@Param("orderId") UUID orderId);

    // ---- 退款单 ----

    /** 还在路上的退款（已建未提交、处理中）的金额合计：算还能退多少时要扣掉。 */
    @Select("""
            SELECT COALESCE(sum(amount_minor), 0) FROM trade_refund
            WHERE order_id = #{orderId} AND status IN ('REQUESTED', 'PROCESSING')
            """)
    long sumOpenRefunds(@Param("orderId") UUID orderId);

    /**
     * 还占着钱的退款合计：在路上的加上“异常”的。异常的退款在微信商户平台人工处理后仍可能退成功，
     * 所以算还能退多少时也要扣掉它，免得同一笔钱退两次。
     */
    @Select("""
            SELECT COALESCE(sum(amount_minor), 0) FROM trade_refund
            WHERE order_id = #{orderId} AND status IN ('REQUESTED', 'PROCESSING', 'ABNORMAL')
            """)
    long sumReservedRefunds(@Param("orderId") UUID orderId);

    @Select("SELECT count(*) FROM trade_refund WHERE order_id = #{orderId}")
    int countRefunds(@Param("orderId") UUID orderId);

    @Select(REFUND_COLUMNS + """
            WHERE r.order_id = #{orderId} AND r.idempotency_key = #{idempotencyKey}
            """)
    RefundRow findRefundByKey(@Param("orderId") UUID orderId, @Param("idempotencyKey") UUID idempotencyKey);

    @Select(REFUND_COLUMNS + """
            WHERE r.id = #{refundId}
            FOR UPDATE
            """)
    RefundRow lockRefund(@Param("refundId") UUID refundId);

    @Select(REFUND_COLUMNS + """
            WHERE r.out_refund_no = #{outRefundNo}
            """)
    RefundRow findRefundByOutRefundNo(@Param("outRefundNo") String outRefundNo);

    @Select("SELECT count(*) FROM trade_refund WHERE out_refund_no = #{outRefundNo}")
    int countOutRefundNo(@Param("outRefundNo") String outRefundNo);

    /** 用 @Select 跑 INSERT … RETURNING 时 MyBatis 不会自己清会话缓存，所以显式清掉。 */
    @Select("""
            INSERT INTO trade_refund (order_id, payment_id, out_refund_no, amount_minor, reason, initiator,
                                      requested_by, idempotency_key, created_at, updated_at)
            VALUES (#{orderId}, #{paymentId}, #{outRefundNo}, #{amountMinor}, #{reason}, #{initiator},
                    #{requestedBy}, #{idempotencyKey}, #{at}, #{at})
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertRefund(
            @Param("orderId") UUID orderId,
            @Param("paymentId") UUID paymentId,
            @Param("outRefundNo") String outRefundNo,
            @Param("amountMinor") long amountMinor,
            @Param("reason") String reason,
            @Param("initiator") String initiator,
            @Param("requestedBy") UUID requestedBy,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("at") Instant at
    );

    /** 渠道受理了：已建 → 处理中，记下微信退款单号和受理时间（对账按受理日期分天）。 */
    @Update("""
            UPDATE trade_refund
            SET status = 'PROCESSING',
                refund_id = #{refundId},
                accepted_at = COALESCE(accepted_at, #{acceptedAt}),
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{id} AND status = 'REQUESTED'
            """)
    int markProcessing(
            @Param("id") UUID id,
            @Param("refundId") String refundId,
            @Param("acceptedAt") Instant acceptedAt,
            @Param("at") Instant at
    );

    /** 渠道确认到账。异常后人工处理成功的（ABNORMAL → SUCCESS）也走这里。 */
    @Update("""
            UPDATE trade_refund
            SET status = 'SUCCEEDED',
                refund_id = COALESCE(#{refundId}, refund_id),
                succeeded_at = #{succeededAt},
                -- 没见过受理就直接到账（回调比受理应答先到）：受理时间按到账时间记。
                accepted_at = COALESCE(accepted_at, #{succeededAt}),
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{id} AND status IN ('REQUESTED', 'PROCESSING', 'ABNORMAL')
            """)
    int markSucceeded(
            @Param("id") UUID id,
            @Param("refundId") String refundId,
            @Param("succeededAt") Instant succeededAt,
            @Param("at") Instant at
    );

    /** 关闭（渠道拒绝或关闭）或异常（要人工处理）。 */
    @Update("""
            UPDATE trade_refund
            SET status = #{status},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{id} AND status IN ('REQUESTED', 'PROCESSING', 'ABNORMAL')
              AND status <> #{status}
            """)
    int markFinished(@Param("id") UUID id, @Param("status") String status, @Param("at") Instant at);

    /** 查过一次还没结果：只更新时间，免得下一轮马上又查。 */
    @Update("UPDATE trade_refund SET updated_at = #{at} WHERE id = #{id}")
    int touch(@Param("id") UUID id, @Param("at") Instant at);

    // ---- 定时兜底 ----

    /** 还没提交成功的退款（渠道不通、限频）：一段时间后用同一个退款单号重新提交。 */
    @Select(REFUND_COLUMNS + """
            WHERE r.status = 'REQUESTED' AND r.updated_at <= #{before}
            ORDER BY r.updated_at ASC, r.id ASC
            LIMIT #{limit}
            """)
    List<RefundRow> findRefundsToSubmit(@Param("before") Instant before, @Param("limit") int limit);

    /** 处理中的隔一会儿查一次；异常的（等人工在微信商户平台处理）隔得久一点再查。 */
    @Select(REFUND_COLUMNS + """
            WHERE (r.status = 'PROCESSING' AND r.updated_at <= #{processingBefore})
               OR (r.status = 'ABNORMAL' AND r.updated_at <= #{abnormalBefore})
            ORDER BY r.updated_at ASC, r.id ASC
            LIMIT #{limit}
            """)
    List<RefundRow> findRefundsToQuery(
            @Param("processingBefore") Instant processingBefore,
            @Param("abnormalBefore") Instant abnormalBefore,
            @Param("limit") int limit
    );

    // ---- 行 ----

    record RefundOrderRow(
            UUID id,
            UUID buyerId,
            UUID merchantId,
            String status,
            String subMchid,
            long paidMinor,
            long refundedMinor,
            Instant paidAt,
            int version
    ) {
    }

    record PaidPaymentRow(UUID id, String outTradeNo, String subMchid, long amountMinor) {
    }

    record RefundRow(
            UUID id,
            UUID orderId,
            UUID paymentId,
            String outRefundNo,
            long amountMinor,
            String reason,
            String initiator,
            String status,
            String refundId,
            UUID idempotencyKey,
            Instant createdAt,
            Instant succeededAt
    ) {
    }
}
