package com.lowaltitude.inspection.mall.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.mall.application.MallRows.OfferRow;
import com.lowaltitude.inspection.mall.application.MallRows.PackItemRow;
import com.lowaltitude.inspection.mall.application.MallRows.ProductCardRow;
import com.lowaltitude.inspection.mall.application.MallRows.ProductRow;
import com.lowaltitude.inspection.mall.application.MallRows.SpecRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 商城目录只读查询。所有条件和排序都用绑定参数，排序是白名单分支，不拼接客户端字段。 */
public interface MallCatalogMapper {

    /** 某城市里某商品的有效供给：商品的有效规格 × 该城市或全国的有效供给。 */
    String CITY_OFFERS = """
            FROM mall_sku sku
            JOIN mall_offer offer ON offer.sku_id = sku.id
            WHERE sku.product_id = p.id
              AND sku.status = 'ACTIVE'
              AND offer.status = 'ACTIVE'
              AND (offer.city_code IS NULL OR offer.city_code = #{cityCode})
            """;

    @Select("""
            SELECT count(*)
            FROM mall_product p
            WHERE p.status = 'PUBLISHED'
              AND (#{category} = 'ALL' OR p.category = #{category})
              AND EXISTS (
                  SELECT 1
            """ + CITY_OFFERS + """
              )
            """)
    long countProducts(
            @Param("cityCode") String cityCode,
            @Param("category") String category
    );

    @Select("""
            SELECT p.id product_id,
                   p.category,
                   p.name,
                   p.cover_url,
                   p.sale_mode,
                   p.experience_badge,
                   city.from_amount_minor,
                   city.price_unit,
                   city.distinct_price_count,
                   city.seller_count
            FROM mall_product p
            JOIN LATERAL (
                SELECT min(offer.price_amount_minor) from_amount_minor,
                       min(offer.price_unit) price_unit,
                       count(DISTINCT offer.price_amount_minor) distinct_price_count,
                       count(DISTINCT offer.merchant_id) seller_count
            """ + CITY_OFFERS + """
            ) city ON city.seller_count > 0
            WHERE p.status = 'PUBLISHED'
              AND (#{category} = 'ALL' OR p.category = #{category})
            ORDER BY CASE WHEN #{sort} IN ('PRICE_ASC', 'PRICE_DESC')
                           AND city.from_amount_minor IS NULL THEN 1 ELSE 0 END ASC,
                     CASE WHEN #{sort} = 'PRICE_ASC' THEN city.from_amount_minor END ASC,
                     CASE WHEN #{sort} = 'PRICE_DESC' THEN city.from_amount_minor END DESC,
                     p.sort_order ASC,
                     p.id ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ProductCardRow> findProducts(
            @Param("cityCode") String cityCode,
            @Param("category") String category,
            @Param("sort") String sort,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select("""
            SELECT id product_id,
                   category,
                   name,
                   summary,
                   cover_url,
                   sale_mode,
                   experience_badge
            FROM mall_product
            WHERE id = #{productId}
              AND status = 'PUBLISHED'
            """)
    Optional<ProductRow> findProduct(@Param("productId") UUID productId);

    @Select("""
            SELECT offer.id offer_id,
                   sku.id sku_id,
                   sku.name sku_name,
                   merchant.id merchant_id,
                   merchant.organization_name merchant_name,
                   offer.merchant_kind,
                   offer.price_amount_minor amount_minor,
                   offer.currency,
                   offer.price_unit,
                   offer.delivery_note,
                   offer.shipping_note,
                   offer.fulfilled_from
            FROM mall_sku sku
            JOIN mall_offer offer ON offer.sku_id = sku.id
            JOIN identity_organization merchant ON merchant.id = offer.merchant_id
            WHERE sku.product_id = #{productId}
              AND sku.status = 'ACTIVE'
              AND offer.status = 'ACTIVE'
              AND (offer.city_code IS NULL OR offer.city_code = #{cityCode})
            ORDER BY offer.price_amount_minor ASC NULLS LAST,
                     sku.sort_order ASC,
                     offer.id ASC
            """)
    List<OfferRow> findOffers(
            @Param("productId") UUID productId,
            @Param("cityCode") String cityCode
    );

    @Select("""
            SELECT label, value
            FROM mall_product_spec
            WHERE product_id = #{productId}
              AND status = 'ACTIVE'
            ORDER BY sort_order ASC, id ASC
            """)
    List<SpecRow> findSpecs(@Param("productId") UUID productId);

    @Select("""
            SELECT name, quantity
            FROM mall_product_pack_item
            WHERE product_id = #{productId}
              AND status = 'ACTIVE'
            ORDER BY sort_order ASC, id ASC
            """)
    List<PackItemRow> findPackItems(@Param("productId") UUID productId);
}
