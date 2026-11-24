package com.lowaltitude.inspection.mall.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.mall.application.MallRows;
import com.lowaltitude.inspection.mall.application.MallRows.OfferRow;
import com.lowaltitude.inspection.mall.application.MallRows.PackItemRow;
import com.lowaltitude.inspection.mall.application.MallRows.ProductCardRow;
import com.lowaltitude.inspection.mall.application.MallRows.ProductRow;
import com.lowaltitude.inspection.mall.application.MallRows.SpecRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 商城目录只读查询。所有条件和排序都用绑定参数，排序是白名单分支，不拼接客户端字段。 */
public interface MallCatalogMapper {

    /**
     * 某城市里某商品的有效供给：商品的有效规格 × 该城市或全国的有效供给；商家资质没审核通过、被挂起上架的不算
     * （身份迁移 V013 的 listing_suspended）。
     */
    String CITY_OFFERS = """
            FROM mall_sku sku
            JOIN mall_offer offer ON offer.sku_id = sku.id
            WHERE sku.product_id = p.id
              AND sku.status = 'ACTIVE'
              AND offer.status = 'ACTIVE'
              AND (offer.city_code IS NULL OR offer.city_code = #{cityCode})
              AND NOT EXISTS (
                  SELECT 1
                  FROM identity_organization suspended
                  WHERE suspended.id = offer.merchant_id
                    AND suspended.listing_suspended
              )
            """;

    /** 该城市里有供给的已发布商品，带上按该城市供给算出的最低价、价格数和商家数。 */
    String CITY_PRODUCTS = """
            FROM mall_product p
            JOIN LATERAL (
                SELECT min(offer.price_amount_minor) from_amount_minor,
                       min(offer.price_unit) price_unit,
                       count(DISTINCT offer.price_amount_minor) distinct_price_count,
                       count(DISTINCT offer.merchant_id) seller_count
            """ + CITY_OFFERS + """
            <if test="merchantId != null">
                  AND offer.merchant_id = #{merchantId}
            </if>
            ) city ON city.seller_count > 0
            WHERE p.status = 'PUBLISHED'
              AND (#{category} = 'ALL' OR p.category = #{category})
            """;

    /**
     * 搜索和筛选。关键词是服务层转义好的 ILIKE 模式；列表为空表示不限。
     * 商家类型看的是该城市的有效供给里有没有这类商家；价格区间比的是该城市的最低标价，
     * 没有标价的询价商品在设置区间后不出现。
     */
    String MATCHING_FILTERS = """
            <if test="keyword != null">
              AND (p.name ILIKE #{keyword} ESCAPE '\\' OR p.summary ILIKE #{keyword} ESCAPE '\\')
            </if>
            <if test="saleModes != null and !saleModes.isEmpty()">
              AND p.sale_mode IN
              <foreach collection="saleModes" item="saleMode"
                       open="(" separator="," close=")">#{saleMode}</foreach>
            </if>
            <if test="experienceBadges != null and !experienceBadges.isEmpty()">
              AND p.experience_badge IN
              <foreach collection="experienceBadges" item="experienceBadge"
                       open="(" separator="," close=")">#{experienceBadge}</foreach>
            </if>
            <if test="merchantKinds != null and !merchantKinds.isEmpty()">
              AND EXISTS (
                  SELECT 1
            """ + CITY_OFFERS + """
                    AND offer.merchant_kind IN
                    <foreach collection="merchantKinds" item="merchantKind"
                             open="(" separator="," close=")">#{merchantKind}</foreach>
              )
            </if>
            <if test="minPriceMinor != null">
              AND city.from_amount_minor &gt;= #{minPriceMinor}
            </if>
            <if test="maxPriceMinor != null">
              AND city.from_amount_minor &lt;= #{maxPriceMinor}
            </if>
            """;

    @Select("<script>SELECT count(*)\n" + CITY_PRODUCTS + MATCHING_FILTERS + "</script>")
    long countProducts(
            @Param("cityCode") String cityCode,
            @Param("category") String category,
            @Param("keyword") String keyword,
            @Param("saleModes") List<String> saleModes,
            @Param("experienceBadges") List<String> experienceBadges,
            @Param("merchantKinds") List<String> merchantKinds,
            @Param("minPriceMinor") Long minPriceMinor,
            @Param("maxPriceMinor") Long maxPriceMinor,
            @Param("merchantId") UUID merchantId
    );

    @Select("<script>" + """
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
            """ + CITY_PRODUCTS + MATCHING_FILTERS + """
            ORDER BY CASE WHEN #{sort} IN ('PRICE_ASC', 'PRICE_DESC')
                           AND city.from_amount_minor IS NULL THEN 1 ELSE 0 END ASC,
                     CASE WHEN #{sort} = 'PRICE_ASC' THEN city.from_amount_minor END ASC,
                     CASE WHEN #{sort} = 'PRICE_DESC' THEN city.from_amount_minor END DESC,
                     p.sort_order ASC,
                     p.id ASC
            LIMIT #{limit} OFFSET #{offset}
            """ + "</script>")
    List<ProductCardRow> findProducts(
            @Param("cityCode") String cityCode,
            @Param("category") String category,
            @Param("keyword") String keyword,
            @Param("saleModes") List<String> saleModes,
            @Param("experienceBadges") List<String> experienceBadges,
            @Param("merchantKinds") List<String> merchantKinds,
            @Param("minPriceMinor") Long minPriceMinor,
            @Param("maxPriceMinor") Long maxPriceMinor,
            @Param("merchantId") UUID merchantId,
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
                   offer.fulfilled_from,
                   offer.stock_quantity
            FROM mall_sku sku
            JOIN mall_offer offer ON offer.sku_id = sku.id
            JOIN identity_organization merchant ON merchant.id = offer.merchant_id
            WHERE sku.product_id = #{productId}
              AND sku.status = 'ACTIVE'
              AND offer.status = 'ACTIVE'
              AND NOT merchant.listing_suspended
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

    // 1.4.0（S4-4）：开票方、售后责任方（供给 → 商家资料 → 商家名称），商品是否支持七天无理由。

    @Select("""
            <script>
            SELECT offer.id offer_id,
                   COALESCE(NULLIF(btrim(offer.invoice_issuer), ''), NULLIF(btrim(profile.invoice_issuer), ''),
                            merchant.organization_name) invoice_issuer,
                   COALESCE(NULLIF(btrim(offer.aftersale_party), ''), NULLIF(btrim(profile.aftersale_party), ''),
                            merchant.organization_name) aftersale_party,
                   product.no_reason_return
            FROM mall_offer offer
            JOIN mall_sku sku ON sku.id = offer.sku_id
            JOIN mall_product product ON product.id = sku.product_id
            JOIN identity_organization merchant ON merchant.id = offer.merchant_id
            LEFT JOIN mall_merchant_profile profile ON profile.organization_id = offer.merchant_id
            WHERE offer.id IN
            <foreach collection="offerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<MallRows.TradeTermsRow> findTradeTerms(@Param("offerIds") List<UUID> offerIds);

    @Select("SELECT no_reason_return FROM mall_product WHERE id = #{productId}")
    Boolean findNoReasonReturn(@Param("productId") UUID productId);

    // 1.3.0（S3-1）：卖点、全部启用规格、图集、图文介绍。

    @Select("""
            SELECT label
            FROM mall_product_selling_point
            WHERE product_id = #{productId}
            ORDER BY sort_order ASC, id ASC
            """)
    List<String> findSellingPoints(@Param("productId") UUID productId);

    @Select("""
            SELECT id sku_id, name
            FROM mall_sku
            WHERE product_id = #{productId}
              AND status = 'ACTIVE'
            ORDER BY sort_order ASC, id ASC
            """)
    List<MallRows.SkuRow> findSkus(@Param("productId") UUID productId);

    @Select("""
            SELECT url
            FROM mall_product_image
            WHERE product_id = #{productId}
            ORDER BY sort_order ASC, id ASC
            """)
    List<String> findGallery(@Param("productId") UUID productId);

    @Select("""
            SELECT block_type, text, image_url
            FROM mall_product_intro_block
            WHERE product_id = #{productId}
            ORDER BY sort_order ASC, id ASC
            """)
    List<MallRows.IntroBlockRow> findIntroBlocks(@Param("productId") UUID productId);
}
