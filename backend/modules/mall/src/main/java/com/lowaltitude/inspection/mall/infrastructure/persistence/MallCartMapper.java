package com.lowaltitude.inspection.mall.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 购物车的读写。每条语句都以账号为条件，全部用绑定参数。
 * 读取时把供给、规格、商品、商家的当前状态一起带出来，能不能买由服务按当前城市判断。
 */
public interface MallCartMapper {

    /** 一个供给的完整情况：加入购物车和结算预览都按它判断。 */
    String OFFER_COLUMNS = """
            SELECT offer.id offer_id,
                   offer.status offer_status,
                   offer.city_code offer_city_code,
                   offer.price_amount_minor unit_amount_minor,
                   offer.currency,
                   offer.price_unit,
                   offer.shipping_note,
                   offer.fulfilled_from,
                   offer.express_available,
                   offer.pickup_point,
                   offer.shipping_fee_minor,
                   offer.merchant_kind,
                   merchant.id merchant_id,
                   merchant.organization_name merchant_name,
                   sku.id sku_id,
                   sku.name sku_name,
                   sku.status sku_status,
                   p.id product_id,
                   p.name product_name,
                   p.cover_url,
                   p.sale_mode,
                   p.status product_status,
                   offer.stock_quantity
            FROM mall_offer offer
            JOIN mall_sku sku ON sku.id = offer.sku_id
            JOIN mall_product p ON p.id = sku.product_id
            JOIN identity_organization merchant ON merchant.id = offer.merchant_id
            """;

    @Select(OFFER_COLUMNS + """
            WHERE offer.id = #{offerId}
            """)
    OfferRow findOffer(@Param("offerId") UUID offerId);

    @Select("""
            SELECT item.id cart_item_id,
                   item.quantity,
                   item.updated_at,
                   offer_view.*
            FROM mall_cart_item item
            JOIN LATERAL (
            """ + OFFER_COLUMNS + """
                WHERE offer.id = item.offer_id
            ) offer_view ON true
            WHERE item.account_id = #{accountId}
            ORDER BY item.updated_at DESC, item.id ASC
            """)
    List<CartRow> findCart(@Param("accountId") UUID accountId);

    /**
     * 同一个账号的写操作排队：事务级的咨询锁，事务结束自动释放。
     * 不加锁时，两个同时到的请求都能通过“数量没到上限”的检查，或者同时换默认而撞上唯一约束。
     */
    @Select("""
            SELECT 1 FROM (
                SELECT pg_advisory_xact_lock(hashtextextended(#{lockName} || CAST(#{accountId} AS text), 0))
            ) locked
            """)
    Integer lockOwner(@Param("lockName") String lockName, @Param("accountId") UUID accountId);

    @Select("""
            SELECT count(*)
            FROM mall_cart_item
            WHERE account_id = #{accountId}
            """)
    int countLines(@Param("accountId") UUID accountId);

    /** 同一个供给已在购物车里时加数量，最多 99；返回这一行的 ID 和加完后的数量。 */
    @Select("""
            INSERT INTO mall_cart_item (account_id, offer_id, quantity, created_at, updated_at)
            VALUES (#{accountId}, #{offerId}, #{quantity}, #{at}, #{at})
            ON CONFLICT (account_id, offer_id) DO UPDATE
            SET quantity = LEAST(99, mall_cart_item.quantity + EXCLUDED.quantity),
                version = mall_cart_item.version + 1,
                updated_at = EXCLUDED.updated_at
            RETURNING id cart_item_id, quantity
            """)
    AddedRow add(
            @Param("accountId") UUID accountId,
            @Param("offerId") UUID offerId,
            @Param("quantity") int quantity,
            @Param("at") Instant at
    );

    @Select("""
            SELECT count(*)
            FROM mall_cart_item
            WHERE account_id = #{accountId}
              AND offer_id = #{offerId}
            """)
    int countOffer(@Param("accountId") UUID accountId, @Param("offerId") UUID offerId);

    @Update("""
            UPDATE mall_cart_item
            SET quantity = #{quantity},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{cartItemId}
              AND account_id = #{accountId}
            """)
    int setQuantity(
            @Param("accountId") UUID accountId,
            @Param("cartItemId") UUID cartItemId,
            @Param("quantity") int quantity,
            @Param("at") Instant at
    );

    @Delete("""
            <script>
            DELETE FROM mall_cart_item
            WHERE account_id = #{accountId}
              AND id IN
            <foreach collection="cartItemIds" item="id" open="(" separator="," close=")">
                #{id}
            </foreach>
            </script>
            """)
    int remove(@Param("accountId") UUID accountId, @Param("cartItemIds") List<UUID> cartItemIds);

    record OfferRow(
            UUID offerId,
            String offerStatus,
            String offerCityCode,
            Long unitAmountMinor,
            String currency,
            String priceUnit,
            String shippingNote,
            String fulfilledFrom,
            boolean expressAvailable,
            String pickupPoint,
            long shippingFeeMinor,
            String merchantKind,
            UUID merchantId,
            String merchantName,
            UUID skuId,
            String skuName,
            String skuStatus,
            UUID productId,
            String productName,
            String coverUrl,
            String saleMode,
            String productStatus,
            // 现货件数（V004）；null 表示不限。
            Integer stockQuantity
    ) {
    }

    record CartRow(
            UUID cartItemId,
            int quantity,
            Instant updatedAt,
            UUID offerId,
            String offerStatus,
            String offerCityCode,
            Long unitAmountMinor,
            String currency,
            String priceUnit,
            String shippingNote,
            String fulfilledFrom,
            boolean expressAvailable,
            String pickupPoint,
            long shippingFeeMinor,
            String merchantKind,
            UUID merchantId,
            String merchantName,
            UUID skuId,
            String skuName,
            String skuStatus,
            UUID productId,
            String productName,
            String coverUrl,
            String saleMode,
            String productStatus,
            Integer stockQuantity
    ) {
        public OfferRow offer() {
            return new OfferRow(
                    offerId, offerStatus, offerCityCode, unitAmountMinor, currency, priceUnit,
                    shippingNote, fulfilledFrom, expressAvailable, pickupPoint, shippingFeeMinor,
                    merchantKind, merchantId, merchantName, skuId, skuName, skuStatus,
                    productId, productName, coverUrl, saleMode, productStatus, stockQuantity);
        }
    }

    record AddedRow(UUID cartItemId, int quantity) {
    }
}
