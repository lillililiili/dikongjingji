package com.lowaltitude.inspection.trade.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 业务来源订单（E09，收费活动报名等）的读写：建订单、按业务单找订单、给业务模块锁订单、读退款进度。
 * 订单行、状态变化记录仍用 {@link TradeOrderMapper}。全部用绑定参数，“当前时间”由调用方传入。
 */
public interface TradeSourceMapper {

    String SOURCE_ORDER_COLUMNS = """
            SELECT o.id, o.order_no, o.buyer_id, o.source, o.source_ref, o.status, o.pay_before, o.version
            FROM trade_order o
            """;

    /** 收款机构的名称，下单时快照到订单上。 */
    @Select("""
            SELECT organization_name
            FROM identity_organization
            WHERE id = #{organizationId}
            """)
    String findOrganizationName(@Param("organizationId") UUID organizationId);

    @Select(SOURCE_ORDER_COLUMNS + """
            WHERE o.source = #{source}
              AND o.source_ref = #{sourceRef}
            """)
    SourceOrderRow findBySourceRef(@Param("source") String source, @Param("sourceRef") UUID sourceRef);

    @Select(SOURCE_ORDER_COLUMNS + """
            WHERE o.id = #{orderId}
            """)
    SourceOrderRow findSourceOrder(@Param("orderId") UUID orderId);

    /** 业务模块改报名单之前先锁交易订单：凡是同时动两边的路径都是先订单、后业务单。 */
    @Select(SOURCE_ORDER_COLUMNS + """
            WHERE o.id = #{orderId}
              AND o.source <> 'MALL'
            FOR UPDATE
            """)
    SourceOrderRow lockSourceOrder(@Param("orderId") UUID orderId);

    /**
     * 业务来源的订单：不发货（配送方式 NONE，没有配送行），没有商家类型；幂等键就是业务单 ID。
     * 用 @Select 跑 INSERT … RETURNING 时 MyBatis 不会自己清会话缓存，所以显式清掉。
     */
    @Select("""
            INSERT INTO trade_order (
                order_no, buyer_id, merchant_id, merchant_kind, merchant_name, sub_mchid, source, source_ref, city_code,
                delivery_mode, goods_minor, shipping_minor, payable_minor, pay_before,
                idempotency_key, request_hash, created_at, updated_at
            )
            VALUES (
                #{orderNo}, #{buyerId}, #{merchantId}, NULL, #{merchantName}, #{subMchid}, #{source}, #{sourceRef},
                #{cityCode}, 'NONE', #{goodsMinor}, 0, #{goodsMinor}, #{payBefore},
                #{sourceRef}, #{requestHash}, #{at}, #{at}
            )
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertSourceOrder(
            @Param("orderNo") String orderNo,
            @Param("buyerId") UUID buyerId,
            @Param("merchantId") UUID merchantId,
            @Param("merchantName") String merchantName,
            @Param("subMchid") String subMchid,
            @Param("source") String source,
            @Param("sourceRef") UUID sourceRef,
            @Param("cityCode") String cityCode,
            @Param("goodsMinor") long goodsMinor,
            @Param("payBefore") Instant payBefore,
            @Param("requestHash") String requestHash,
            @Param("at") Instant at
    );

    /** 退款进度：业务模块按自己记下的退款单 ID 读。 */
    @Select("""
            <script>
            SELECT id, order_id, amount_minor, status
            FROM trade_refund
            WHERE id IN
            <foreach collection="refundIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<SourceRefundRow> findRefunds(@Param("refundIds") List<UUID> refundIds);

    record SourceOrderRow(
            UUID id,
            String orderNo,
            UUID buyerId,
            String source,
            UUID sourceRef,
            String status,
            Instant payBefore,
            int version
    ) {
    }

    record SourceRefundRow(UUID id, UUID orderId, long amountMinor, String status) {
    }
}
