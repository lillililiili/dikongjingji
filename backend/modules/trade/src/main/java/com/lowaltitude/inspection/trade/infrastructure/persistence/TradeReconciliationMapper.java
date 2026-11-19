package com.lowaltitude.inspection.trade.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 对账（E06）的读写。一天按北京时间的自然日 [from, to) 取平台的支付和退款；“当前时间”由调用方传入。
 */
public interface TradeReconciliationMapper {

    /** 同一天、同一个子商户的对账排队：定时任务和手动重跑撞在一起时，后到的在锁后看到先到的结果。 */
    @Select("""
            SELECT 1 FROM (
                SELECT pg_advisory_xact_lock(hashtextextended(
                    'trade-reconciliation:' || CAST(#{billDate} AS text) || ':' || #{subMchid}, 0))
            ) locked
            """)
    Integer lock(@Param("billDate") LocalDate billDate, @Param("subMchid") String subMchid);

    /**
     * 这一天要对账的子商户：这一天结束前就登记了、现在仍启用的收款账户，加上那天有付款或退款受理的（账户后来停用的也要对）。
     * 之后才登记的账户不算进以前的日子，免得新登记一个商家就让历史日期全都“未就绪”。
     */
    @Select("""
            SELECT sub_mchid FROM trade_merchant_account WHERE active AND created_at < #{to}
            UNION
            SELECT p.sub_mchid FROM trade_payment p
            WHERE p.succeeded_at >= #{from} AND p.succeeded_at < #{to}
            UNION
            SELECT p.sub_mchid FROM trade_refund r
            JOIN trade_payment p ON p.id = r.payment_id
            WHERE r.accepted_at >= #{from} AND r.accepted_at < #{to}
            ORDER BY 1
            """)
    List<String> findSubMchidsToReconcile(@Param("from") Instant from, @Param("to") Instant to);

    // ---- 平台这边的单据 ----

    String PAYMENT_COLUMNS = "SELECT p.out_trade_no, p.sub_mchid, p.amount_minor, p.status FROM trade_payment p ";

    @Select("<script>" + PAYMENT_COLUMNS + """
            WHERE p.out_trade_no IN
            <foreach collection="outTradeNos" item="no" open="(" separator="," close=")">#{no}</foreach>
            </script>
            """)
    List<BillPaymentRow> findPaymentsByOutTradeNos(@Param("outTradeNos") List<String> outTradeNos);

    /** 平台记为付款成功、付款时间落在这一天的支付单。 */
    @Select(PAYMENT_COLUMNS + """
            WHERE p.sub_mchid = #{subMchid}
              AND p.status = 'SUCCEEDED'
              AND p.succeeded_at >= #{from} AND p.succeeded_at < #{to}
            ORDER BY p.out_trade_no
            """)
    List<BillPaymentRow> findPaymentsSucceededBetween(
            @Param("subMchid") String subMchid,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    String REFUND_COLUMNS = """
            SELECT r.out_refund_no, p.out_trade_no, p.sub_mchid, r.amount_minor, r.status
            FROM trade_refund r
            JOIN trade_payment p ON p.id = r.payment_id
            """;

    @Select("<script>" + REFUND_COLUMNS + """
            WHERE r.out_refund_no IN
            <foreach collection="outRefundNos" item="no" open="(" separator="," close=")">#{no}</foreach>
            </script>
            """)
    List<BillRefundRow> findRefundsByOutRefundNos(@Param("outRefundNos") List<String> outRefundNos);

    /** 微信受理时间落在这一天的退款（账单按受理日期出退款行）。 */
    @Select(REFUND_COLUMNS + """
            WHERE p.sub_mchid = #{subMchid}
              AND r.accepted_at >= #{from} AND r.accepted_at < #{to}
            ORDER BY r.out_refund_no
            """)
    List<BillRefundRow> findRefundsAcceptedBetween(
            @Param("subMchid") String subMchid,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    // ---- 对账结果 ----

    @Select("""
            SELECT id, bill_date, sub_mchid, status, matched_count, failure_reason, imported_at
            FROM trade_reconciliation
            WHERE bill_date = #{billDate} AND sub_mchid = #{subMchid}
            """)
    ReconciliationRow findReconciliation(@Param("billDate") LocalDate billDate, @Param("subMchid") String subMchid);

    @Select("""
            SELECT id, bill_date, sub_mchid, status, matched_count, failure_reason, imported_at
            FROM trade_reconciliation
            WHERE bill_date = #{billDate}
            ORDER BY sub_mchid
            """)
    List<ReconciliationRow> findReconciliations(@Param("billDate") LocalDate billDate);

    /** 记下这一天、这个子商户的结果（重跑时覆盖）。用 @Select 跑 INSERT … RETURNING，显式清会话缓存。 */
    @Select("""
            INSERT INTO trade_reconciliation (bill_date, sub_mchid, status, matched_count, failure_reason, imported_at)
            VALUES (#{billDate}, #{subMchid}, #{status}, #{matchedCount}, #{failureReason}, #{at})
            ON CONFLICT (bill_date, sub_mchid) DO UPDATE
            SET status = EXCLUDED.status,
                matched_count = EXCLUDED.matched_count,
                failure_reason = EXCLUDED.failure_reason,
                imported_at = EXCLUDED.imported_at
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID saveReconciliation(
            @Param("billDate") LocalDate billDate,
            @Param("subMchid") String subMchid,
            @Param("status") String status,
            @Param("matchedCount") int matchedCount,
            @Param("failureReason") String failureReason,
            @Param("at") Instant at
    );

    @Update("DELETE FROM trade_reconciliation_difference WHERE reconciliation_id = #{reconciliationId}")
    int deleteDifferences(@Param("reconciliationId") UUID reconciliationId);

    @Update("""
            INSERT INTO trade_reconciliation_difference (reconciliation_id, kind, out_trade_no, out_refund_no,
                                                         platform_amount_minor, channel_amount_minor, created_at)
            VALUES (#{reconciliationId}, #{kind}, #{outTradeNo}, #{outRefundNo}, #{platformAmountMinor},
                    #{channelAmountMinor}, #{at})
            """)
    int insertDifference(
            @Param("reconciliationId") UUID reconciliationId,
            @Param("kind") String kind,
            @Param("outTradeNo") String outTradeNo,
            @Param("outRefundNo") String outRefundNo,
            @Param("platformAmountMinor") Long platformAmountMinor,
            @Param("channelAmountMinor") Long channelAmountMinor,
            @Param("at") Instant at
    );

    @Select("""
            <script>
            SELECT reconciliation_id, kind, out_trade_no, out_refund_no, platform_amount_minor, channel_amount_minor
            FROM trade_reconciliation_difference
            WHERE reconciliation_id IN
            <foreach collection="reconciliationIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY reconciliation_id, id
            </script>
            """)
    List<DifferenceRow> findDifferences(@Param("reconciliationIds") List<UUID> reconciliationIds);

    // ---- 行 ----

    record BillPaymentRow(String outTradeNo, String subMchid, long amountMinor, String status) {
    }

    /** outTradeNo 是原支付单的商户订单号（差异里要能对到订单）。 */
    record BillRefundRow(String outRefundNo, String outTradeNo, String subMchid, long amountMinor, String status) {
    }

    record ReconciliationRow(
            UUID id,
            LocalDate billDate,
            String subMchid,
            String status,
            int matchedCount,
            String failureReason,
            Instant importedAt
    ) {
    }

    record DifferenceRow(
            UUID reconciliationId,
            String kind,
            String outTradeNo,
            String outRefundNo,
            Long platformAmountMinor,
            Long channelAmountMinor
    ) {
    }
}
