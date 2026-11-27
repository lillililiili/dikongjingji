package com.lowaltitude.inspection.mall.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface MallInventoryMapper {
    @Insert("""
            INSERT INTO mall_stock_order(order_id, line_signature) VALUES (#{orderId}, #{signature})
            ON CONFLICT(order_id) DO NOTHING
            """)
    int begin(@Param("orderId") UUID orderId, @Param("signature") String signature);

    @Select("SELECT line_signature FROM mall_stock_order WHERE order_id = #{orderId}")
    String signature(@Param("orderId") UUID orderId);

    @Select("""
            <script>
            SELECT offer_id FROM mall_cart_item
            WHERE account_id = #{accountId}
              AND id IN <foreach collection="itemIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY id FOR UPDATE
            </script>
            """)
    List<UUID> lockCartOffers(@Param("accountId") UUID accountId, @Param("itemIds") List<UUID> itemIds);

    @Select("""
            SELECT o.id AS offer_id, o.stock_quantity
            FROM mall_offer o
            JOIN mall_sku s ON s.id = o.sku_id
            JOIN mall_product p ON p.id = s.product_id
            JOIN identity_organization m ON m.id = o.merchant_id
            WHERE o.id = #{offerId} AND o.status = 'ACTIVE' AND o.price_amount_minor IS NOT NULL AND o.price_unit = 'ITEM'
              AND s.status = 'ACTIVE' AND p.status = 'PUBLISHED' AND p.sale_mode = 'BUY'
              AND m.status = 'ACTIVE' AND NOT m.listing_suspended
            FOR UPDATE OF o
            """)
    Offer lockPurchasable(@Param("offerId") UUID offerId);

    @Update("""
            UPDATE mall_offer SET stock_quantity = stock_quantity - #{qty},
                version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{offerId} AND stock_quantity >= #{qty}
            """)
    int deduct(@Param("offerId") UUID offerId, @Param("qty") int quantity);

    @Insert("""
            INSERT INTO mall_stock_hold(order_id, offer_id, quantity, status)
            VALUES (#{orderId}, #{offerId}, #{qty}, 'HELD')
            """)
    void hold(@Param("orderId") UUID orderId, @Param("offerId") UUID offerId, @Param("qty") int quantity);

    @Select("""
            SELECT offer_id, quantity, status FROM mall_stock_hold
            WHERE order_id = #{orderId} ORDER BY offer_id FOR UPDATE
            """)
    List<Hold> lockHolds(@Param("orderId") UUID orderId);

    @Update("""
            UPDATE mall_offer SET stock_quantity = stock_quantity + #{qty},
                version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = #{offerId} AND stock_quantity IS NOT NULL
            """)
    void restore(@Param("offerId") UUID offerId, @Param("qty") int quantity);

    @Update("""
            UPDATE mall_stock_hold SET status = #{target}, updated_at = CURRENT_TIMESTAMP
            WHERE order_id = #{orderId} AND status = 'HELD'
            """)
    void settle(@Param("orderId") UUID orderId, @Param("target") String target);

    record Offer(UUID offerId, Integer stockQuantity) { }
    record Hold(UUID offerId, int quantity, String status) { }
}
