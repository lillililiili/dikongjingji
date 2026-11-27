package com.lowaltitude.inspection.mall.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 运营端“商品与供给”（S3-1，迁移 V004）和批量导入写商品的读写。全部用绑定参数，不拼接客户端字段；
 * 已删除（DELETED）的商品当作不存在。商家名称取自身份模块的机构表（和商城目录读取一样只读）。
 */
public interface MallCatalogOpsMapper {

    String LIST_FILTER = """
            FROM mall_product p
            WHERE p.status != 'DELETED'
            <if test="keyword != null">
              AND p.name ILIKE #{keyword} ESCAPE '\\'
            </if>
            <if test="category != null">
              AND p.category = #{category}
            </if>
            <if test="saleMode != null">
              AND p.sale_mode = #{saleMode}
            </if>
            <if test="status != null">
              AND p.status = #{status}
            </if>
            <if test="ownerId != null">
              AND p.owner_organization_id = #{ownerId}
            </if>
            """;

    /** 建这件商品的商家（V006）；平台建的为空。名称取自身份模块的机构表（只读）。 */
    String OWNER_COLUMNS = """
            p.owner_organization_id,
                   (SELECT o.organization_name FROM identity_organization o WHERE o.id = p.owner_organization_id) owner_name
            """;

    @Select("<script>SELECT count(*)\n" + LIST_FILTER + "</script>")
    long countProducts(@Param("keyword") String keyword, @Param("category") String category,
                       @Param("saleMode") String saleMode, @Param("status") String status,
                       @Param("ownerId") UUID ownerId);

    @Select("<script>" + """
            SELECT p.id product_id,
                   p.category,
                   p.name,
                   p.sale_mode,
                   p.status,
                   p.cover_url,
                   (SELECT count(*) FROM mall_sku s WHERE s.product_id = p.id AND s.status = 'ACTIVE') sku_count,
                   (SELECT count(*)
                      FROM mall_sku s
                      JOIN mall_offer o ON o.sku_id = s.id
                     WHERE s.product_id = p.id AND o.status = 'ACTIVE') active_offer_count,
                   p.updated_at,
                   p.version,
                   """ + OWNER_COLUMNS + LIST_FILTER + """
            ORDER BY p.updated_at DESC, p.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """ + "</script>")
    List<SummaryRow> findProducts(@Param("keyword") String keyword, @Param("category") String category,
                                  @Param("saleMode") String saleMode, @Param("status") String status,
                                  @Param("ownerId") UUID ownerId, @Param("limit") int limit, @Param("offset") int offset);

    String PRODUCT_COLUMNS = """
            SELECT p.id product_id, p.category, p.name, p.summary, p.cover_url, p.sale_mode, p.experience_badge,
                   p.sort_order, p.status, p.created_at, p.updated_at, p.version, p.ops_create_key,
                   """ + OWNER_COLUMNS + """
            FROM mall_product p
            """;

    @Select(PRODUCT_COLUMNS + " WHERE p.id = #{productId} AND p.status <> 'DELETED'")
    ProductRow findProduct(@Param("productId") UUID productId);

    /** 同一商品的运营操作排队：资料、规格、供给、上下架不会交错。 */
    @Select("SELECT p.id FROM mall_product p WHERE p.id = #{productId} AND p.status <> 'DELETED' FOR UPDATE")
    UUID lockProduct(@Param("productId") UUID productId);

    @Select("SELECT id FROM mall_product WHERE ops_create_key = #{createKey}")
    UUID findProductByCreateKey(@Param("createKey") UUID createKey);

    /** 新建商品（草稿）；同一个幂等键已经建过时什么都不写，返回 null。 */
    @Select("""
            INSERT INTO mall_product (category, name, sale_mode, status, ops_create_key, owner_organization_id,
                                      created_at, updated_at)
            VALUES (#{category}, #{name}, #{saleMode}, 'DRAFT', #{createKey}, #{ownerId}, #{at}, #{at})
            ON CONFLICT (ops_create_key) WHERE ops_create_key IS NOT NULL DO NOTHING
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertProduct(@Param("category") String category, @Param("name") String name,
                       @Param("saleMode") String saleMode, @Param("createKey") UUID createKey,
                       @Param("ownerId") UUID ownerId, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE mall_product
            SET category = #{category},
                name = #{name},
                summary = #{summary},
                cover_url = #{coverUrl},
                experience_badge = #{experienceBadge},
                sort_order = #{sortOrder},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{productId}
              AND version = #{version}
              AND status <> 'DELETED'
            """)
    int updateProduct(@Param("productId") UUID productId, @Param("category") String category,
                      @Param("name") String name, @Param("summary") String summary,
                      @Param("coverUrl") String coverUrl, @Param("experienceBadge") String experienceBadge,
                      @Param("sortOrder") int sortOrder, @Param("version") int version,
                      @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE mall_product
            SET status = #{status},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{productId}
              AND version = #{version}
              AND status <> 'DELETED'
            """)
    int updateProductStatus(@Param("productId") UUID productId, @Param("status") String status,
                            @Param("version") int version, @Param("at") OffsetDateTime at);

    /** 删除草稿：只标成 DELETED，资料和操作记录都留着。上架过的（PUBLISHED、OFFLINE）不会命中。 */
    @Update("""
            UPDATE mall_product
            SET status = 'DELETED',
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{productId}
              AND version = #{version}
              AND status = 'DRAFT'
            """)
    int deleteDraft(@Param("productId") UUID productId, @Param("version") int version,
                    @Param("at") OffsetDateTime at);

    /** 规格、供给变了：商品的版本号和更新时间跟着变（列表按更新时间排，页面上的版本号也就不再有效）。 */
    @Update("""
            UPDATE mall_product
            SET version = version + 1,
                updated_at = #{at}
            WHERE id = #{productId}
            """)
    int touchProduct(@Param("productId") UUID productId, @Param("at") OffsetDateTime at);

    // ---- 卖点、参数、包装清单、图集、图文：整份替换 ----

    @Select("SELECT label FROM mall_product_selling_point WHERE product_id = #{productId} ORDER BY sort_order, id")
    List<String> findSellingPoints(@Param("productId") UUID productId);

    @Delete("DELETE FROM mall_product_selling_point WHERE product_id = #{productId}")
    int deleteSellingPoints(@Param("productId") UUID productId);

    @Insert("""
            INSERT INTO mall_product_selling_point (product_id, label, sort_order, created_at)
            VALUES (#{productId}, #{label}, #{sortOrder}, #{at})
            """)
    int insertSellingPoint(@Param("productId") UUID productId, @Param("label") String label,
                           @Param("sortOrder") int sortOrder, @Param("at") OffsetDateTime at);

    @Select("""
            SELECT label, value
            FROM mall_product_spec
            WHERE product_id = #{productId} AND status = 'ACTIVE'
            ORDER BY sort_order, id
            """)
    List<SpecRow> findSpecs(@Param("productId") UUID productId);

    /** 运营端整份替换参数：旧的标成停用（记录保留），再写新的。 */
    @Update("""
            UPDATE mall_product_spec
            SET status = 'DISABLED', version = version + 1, updated_at = #{at}
            WHERE product_id = #{productId} AND status = 'ACTIVE'
            """)
    int disableSpecs(@Param("productId") UUID productId, @Param("at") OffsetDateTime at);

    @Insert("""
            INSERT INTO mall_product_spec (product_id, label, value, sort_order, created_at, updated_at)
            VALUES (#{productId}, #{label}, #{value}, #{sortOrder}, #{at}, #{at})
            """)
    int insertSpec(@Param("productId") UUID productId, @Param("label") String label, @Param("value") String value,
                   @Param("sortOrder") int sortOrder, @Param("at") OffsetDateTime at);

    @Select("""
            SELECT name, quantity
            FROM mall_product_pack_item
            WHERE product_id = #{productId} AND status = 'ACTIVE'
            ORDER BY sort_order, id
            """)
    List<PackRow> findPackItems(@Param("productId") UUID productId);

    @Update("""
            UPDATE mall_product_pack_item
            SET status = 'DISABLED', version = version + 1, updated_at = #{at}
            WHERE product_id = #{productId} AND status = 'ACTIVE'
            """)
    int disablePackItems(@Param("productId") UUID productId, @Param("at") OffsetDateTime at);

    @Insert("""
            INSERT INTO mall_product_pack_item (product_id, name, quantity, sort_order, created_at, updated_at)
            VALUES (#{productId}, #{name}, #{quantity}, #{sortOrder}, #{at}, #{at})
            """)
    int insertPackItem(@Param("productId") UUID productId, @Param("name") String name,
                       @Param("quantity") int quantity, @Param("sortOrder") int sortOrder,
                       @Param("at") OffsetDateTime at);

    @Select("SELECT url FROM mall_product_image WHERE product_id = #{productId} ORDER BY sort_order, id")
    List<String> findGallery(@Param("productId") UUID productId);

    @Delete("DELETE FROM mall_product_image WHERE product_id = #{productId}")
    int deleteGallery(@Param("productId") UUID productId);

    @Insert("""
            INSERT INTO mall_product_image (product_id, url, sort_order, created_at)
            VALUES (#{productId}, #{url}, #{sortOrder}, #{at})
            """)
    int insertGalleryImage(@Param("productId") UUID productId, @Param("url") String url,
                           @Param("sortOrder") int sortOrder, @Param("at") OffsetDateTime at);

    @Select("""
            SELECT block_type, text, image_url
            FROM mall_product_intro_block
            WHERE product_id = #{productId}
            ORDER BY sort_order, id
            """)
    List<IntroRow> findIntroBlocks(@Param("productId") UUID productId);

    @Delete("DELETE FROM mall_product_intro_block WHERE product_id = #{productId}")
    int deleteIntroBlocks(@Param("productId") UUID productId);

    @Insert("""
            INSERT INTO mall_product_intro_block (product_id, block_type, text, image_url, sort_order, created_at)
            VALUES (#{productId}, #{type}, #{text}, #{imageUrl}, #{sortOrder}, #{at})
            """)
    int insertIntroBlock(@Param("productId") UUID productId, @Param("type") String type, @Param("text") String text,
                         @Param("imageUrl") String imageUrl, @Param("sortOrder") int sortOrder,
                         @Param("at") OffsetDateTime at);

    // ---- 规格 ----

    @Select("""
            SELECT id sku_id, product_id, name, sort_order, status, version
            FROM mall_sku
            WHERE product_id = #{productId}
            ORDER BY sort_order, id
            """)
    List<SkuRow> findSkus(@Param("productId") UUID productId);

    @Select("""
            INSERT INTO mall_sku (product_id, name, sort_order, created_at, updated_at)
            VALUES (#{productId}, #{name}, #{sortOrder}, #{at}, #{at})
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertSku(@Param("productId") UUID productId, @Param("name") String name,
                   @Param("sortOrder") int sortOrder, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE mall_sku
            SET name = #{name},
                sort_order = #{sortOrder},
                status = #{status},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{skuId}
              AND product_id = #{productId}
              AND version = #{version}
            """)
    int updateSku(@Param("productId") UUID productId, @Param("skuId") UUID skuId, @Param("name") String name,
                  @Param("sortOrder") int sortOrder, @Param("status") String status, @Param("version") int version,
                  @Param("at") OffsetDateTime at);

    // ---- 供给 ----

    String OFFER_COLUMNS = """
            SELECT o.id offer_id,
                   o.sku_id,
                   s.name sku_name,
                   o.merchant_id,
                   m.organization_name merchant_name,
                   o.merchant_kind,
                   o.city_code,
                   o.price_amount_minor,
                   o.price_unit,
                   o.stock_quantity,
                   o.express_available,
                   o.shipping_fee_minor,
                   o.pickup_point,
                   o.delivery_note,
                   o.shipping_note,
                   o.fulfilled_from,
                   o.status,
                   o.updated_at,
                   o.version,
                   o.ops_create_key
            FROM mall_offer o
            JOIN mall_sku s ON s.id = o.sku_id
            JOIN identity_organization m ON m.id = o.merchant_id
            """;

    @Select(OFFER_COLUMNS + """
            WHERE s.product_id = #{productId}
            ORDER BY s.sort_order, s.id, o.status, o.price_amount_minor NULLS LAST, o.id
            """)
    List<OfferRow> findOffers(@Param("productId") UUID productId);

    @Select(OFFER_COLUMNS + " WHERE o.id = #{offerId} AND s.product_id = #{productId}")
    OfferRow findOffer(@Param("productId") UUID productId, @Param("offerId") UUID offerId);

    @Select("SELECT id FROM mall_offer WHERE ops_create_key = #{createKey}")
    UUID findOfferByCreateKey(@Param("createKey") UUID createKey);

    /** 同一个规格、商家、城市（空着算全国）的供给，除了 exceptOfferId 这一条。 */
    @Select("""
            SELECT count(*)
            FROM mall_offer
            WHERE sku_id = #{skuId}
              AND merchant_id = #{merchantId}
              AND COALESCE(city_code, '*') = COALESCE(CAST(#{cityCode} AS varchar), '*')
              AND (CAST(#{exceptOfferId} AS uuid) IS NULL OR id <> CAST(#{exceptOfferId} AS uuid))
            """)
    int countSameOffer(@Param("skuId") UUID skuId, @Param("merchantId") UUID merchantId,
                       @Param("cityCode") String cityCode, @Param("exceptOfferId") UUID exceptOfferId);

    /** 新建供给（停用）；同一个幂等键已经建过时什么都不写，返回 null。 */
    @Select("""
            INSERT INTO mall_offer (
                sku_id, merchant_id, merchant_kind, city_code, price_amount_minor, price_unit, stock_quantity,
                express_available, shipping_fee_minor, pickup_point, delivery_note, shipping_note, fulfilled_from,
                status, ops_create_key, created_at, updated_at
            )
            VALUES (
                #{skuId}, #{merchantId}, #{fields.merchantKind}, #{fields.cityCode}, #{fields.priceAmountMinor},
                #{fields.priceUnit}, #{fields.stockQuantity}, #{fields.expressAvailable}, #{fields.shippingFeeMinor},
                #{fields.pickupPoint}, #{fields.deliveryNote}, #{fields.shippingNote}, #{fields.fulfilledFrom},
                'DISABLED', #{createKey}, #{at}, #{at}
            )
            ON CONFLICT (ops_create_key) WHERE ops_create_key IS NOT NULL DO NOTHING
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertOffer(@Param("skuId") UUID skuId, @Param("merchantId") UUID merchantId,
                     @Param("fields") OfferFields fields, @Param("createKey") UUID createKey,
                     @Param("at") OffsetDateTime at);

    /** 表单提交的是目标可售数。下单预占和未付关闭也更新版本，拒绝过期表单覆盖现有可售数。 */
    @Update("""
            UPDATE mall_offer
            SET merchant_kind = #{fields.merchantKind},
                city_code = #{fields.cityCode},
                price_amount_minor = #{fields.priceAmountMinor},
                price_unit = #{fields.priceUnit},
                stock_quantity = #{fields.stockQuantity},
                express_available = #{fields.expressAvailable},
                shipping_fee_minor = #{fields.shippingFeeMinor},
                pickup_point = #{fields.pickupPoint},
                delivery_note = #{fields.deliveryNote},
                shipping_note = #{fields.shippingNote},
                fulfilled_from = #{fields.fulfilledFrom},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{offerId}
              AND version = #{version}
            """)
    int updateOffer(@Param("offerId") UUID offerId, @Param("fields") OfferFields fields,
                    @Param("version") int version, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE mall_offer
            SET status = #{status},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{offerId}
              AND version = #{version}
            """)
    int updateOfferStatus(@Param("offerId") UUID offerId, @Param("status") String status,
                          @Param("version") int version, @Param("at") OffsetDateTime at);

    // ---- 批量导入：按导入工具给出的 ID 新建或更新（ID 由导入的对照表决定） ----

    @Select("SELECT EXISTS (SELECT 1 FROM mall_product WHERE id = #{productId})")
    boolean productExists(@Param("productId") UUID productId);

    @Insert("""
            INSERT INTO mall_product (id, category, name, summary, cover_url, sale_mode, experience_badge, sort_order,
                                      status, created_at, updated_at)
            VALUES (#{productId}, #{category}, #{name}, #{summary}, #{coverUrl}, #{saleMode}, #{experienceBadge},
                    #{sortOrder}, #{status}, #{at}, #{at})
            ON CONFLICT (id) DO UPDATE
            SET category = EXCLUDED.category,
                name = EXCLUDED.name,
                summary = EXCLUDED.summary,
                cover_url = EXCLUDED.cover_url,
                sale_mode = EXCLUDED.sale_mode,
                experience_badge = EXCLUDED.experience_badge,
                sort_order = EXCLUDED.sort_order,
                status = EXCLUDED.status,
                version = mall_product.version + 1,
                updated_at = EXCLUDED.updated_at
            """)
    int upsertProduct(@Param("productId") UUID productId, @Param("category") String category,
                      @Param("name") String name, @Param("summary") String summary,
                      @Param("coverUrl") String coverUrl, @Param("saleMode") String saleMode,
                      @Param("experienceBadge") String experienceBadge, @Param("sortOrder") int sortOrder,
                      @Param("status") String status, @Param("at") OffsetDateTime at);

    @Select("SELECT EXISTS (SELECT 1 FROM mall_sku WHERE id = #{skuId})")
    boolean skuExists(@Param("skuId") UUID skuId);

    @Insert("""
            INSERT INTO mall_sku (id, product_id, name, sort_order, status, created_at, updated_at)
            VALUES (#{skuId}, #{productId}, #{name}, #{sortOrder}, 'ACTIVE', #{at}, #{at})
            ON CONFLICT (id) DO UPDATE
            SET product_id = EXCLUDED.product_id,
                name = EXCLUDED.name,
                sort_order = EXCLUDED.sort_order,
                status = 'ACTIVE',
                version = mall_sku.version + 1,
                updated_at = EXCLUDED.updated_at
            """)
    int upsertSku(@Param("skuId") UUID skuId, @Param("productId") UUID productId, @Param("name") String name,
                  @Param("sortOrder") int sortOrder, @Param("at") OffsetDateTime at);

    @Select("SELECT EXISTS (SELECT 1 FROM mall_offer WHERE id = #{offerId})")
    boolean offerExists(@Param("offerId") UUID offerId);

    /** 同一个规格、商家、城市已有的那条供给（导入时沿用它，不新建第二条）。 */
    @Select("""
            SELECT id
            FROM mall_offer
            WHERE sku_id = #{skuId}
              AND merchant_id = #{merchantId}
              AND COALESCE(city_code, '*') = COALESCE(CAST(#{cityCode} AS varchar), '*')
            """)
    UUID findSameOffer(@Param("skuId") UUID skuId, @Param("merchantId") UUID merchantId,
                       @Param("cityCode") String cityCode);

    /** 批量盘点按提供的可售数写回供给；未付订单原预占保存在 mall_stock_hold，成交或关闭时单独处理。 */
    @Insert("""
            INSERT INTO mall_offer (
                id, sku_id, merchant_id, merchant_kind, city_code, price_amount_minor, price_unit, stock_quantity,
                express_available, shipping_fee_minor, pickup_point, delivery_note, shipping_note, fulfilled_from,
                status, created_at, updated_at
            )
            VALUES (
                #{offerId}, #{skuId}, #{merchantId}, #{fields.merchantKind}, #{fields.cityCode},
                #{fields.priceAmountMinor}, #{fields.priceUnit}, #{fields.stockQuantity}, #{fields.expressAvailable},
                #{fields.shippingFeeMinor}, #{fields.pickupPoint}, #{fields.deliveryNote}, #{fields.shippingNote},
                #{fields.fulfilledFrom}, #{status}, #{at}, #{at}
            )
            ON CONFLICT (id) DO UPDATE
            SET sku_id = EXCLUDED.sku_id,
                merchant_id = EXCLUDED.merchant_id,
                merchant_kind = EXCLUDED.merchant_kind,
                city_code = EXCLUDED.city_code,
                price_amount_minor = EXCLUDED.price_amount_minor,
                price_unit = EXCLUDED.price_unit,
                stock_quantity = EXCLUDED.stock_quantity,
                express_available = EXCLUDED.express_available,
                shipping_fee_minor = EXCLUDED.shipping_fee_minor,
                pickup_point = EXCLUDED.pickup_point,
                delivery_note = EXCLUDED.delivery_note,
                shipping_note = EXCLUDED.shipping_note,
                fulfilled_from = EXCLUDED.fulfilled_from,
                status = EXCLUDED.status,
                version = mall_offer.version + 1,
                updated_at = EXCLUDED.updated_at
            """)
    int upsertOffer(@Param("offerId") UUID offerId, @Param("skuId") UUID skuId, @Param("merchantId") UUID merchantId,
                    @Param("fields") OfferFields fields, @Param("status") String status,
                    @Param("at") OffsetDateTime at);

    // ---- 图片和操作记录 ----

    @Insert("""
            INSERT INTO mall_media_asset (id, storage_key, content_type, size_bytes, sha256, url, uploaded_by,
                                          uploaded_at)
            VALUES (#{mediaId}, #{storageKey}, #{contentType}, #{sizeBytes}, #{sha256}, #{url}, #{uploadedBy}, #{at})
            """)
    int insertMedia(@Param("mediaId") UUID mediaId, @Param("storageKey") String storageKey,
                    @Param("contentType") String contentType, @Param("sizeBytes") long sizeBytes,
                    @Param("sha256") String sha256, @Param("url") String url, @Param("uploadedBy") UUID uploadedBy,
                    @Param("at") OffsetDateTime at);

    @Insert("""
            INSERT INTO mall_catalog_operation (product_id, action, detail, operator_id, operator_name, occurred_at)
            VALUES (#{productId}, #{action}, #{detail}, #{operatorId}, #{operatorName}, #{at})
            """)
    int insertOperation(@Param("productId") UUID productId, @Param("action") String action,
                        @Param("detail") String detail, @Param("operatorId") UUID operatorId,
                        @Param("operatorName") String operatorName, @Param("at") OffsetDateTime at);

    @Select("""
            SELECT id, action, detail, operator_name, occurred_at
            FROM mall_catalog_operation
            WHERE product_id = #{productId}
            ORDER BY occurred_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<OperationRow> findOperations(@Param("productId") UUID productId, @Param("limit") int limit);

    record SummaryRow(UUID productId, String category, String name, String saleMode, String status, String coverUrl,
                      int skuCount, int activeOfferCount, OffsetDateTime updatedAt, int version,
                      UUID ownerOrganizationId, String ownerName) {
    }

    record ProductRow(UUID productId, String category, String name, String summary, String coverUrl, String saleMode,
                      String experienceBadge, int sortOrder, String status, OffsetDateTime createdAt,
                      OffsetDateTime updatedAt, int version, UUID opsCreateKey, UUID ownerOrganizationId,
                      String ownerName) {
    }

    record SpecRow(String label, String value) {
    }

    record PackRow(String name, int quantity) {
    }

    record IntroRow(String blockType, String text, String imageUrl) {
    }

    record SkuRow(UUID skuId, UUID productId, String name, int sortOrder, String status, int version) {
    }

    record OfferRow(UUID offerId, UUID skuId, String skuName, UUID merchantId, String merchantName,
                    String merchantKind, String cityCode, Long priceAmountMinor, String priceUnit,
                    Integer stockQuantity, boolean expressAvailable, long shippingFeeMinor, String pickupPoint,
                    String deliveryNote, String shippingNote, String fulfilledFrom, String status,
                    OffsetDateTime updatedAt, int version, UUID opsCreateKey) {
    }

    /** 供给里能改的那些（规格和商家建好后不能改）。 */
    record OfferFields(String merchantKind, String cityCode, Long priceAmountMinor, String priceUnit,
                       Integer stockQuantity, boolean expressAvailable, long shippingFeeMinor, String pickupPoint,
                       String deliveryNote, String shippingNote, String fulfilledFrom) {
    }

    record OperationRow(UUID id, String action, String detail, String operatorName, OffsetDateTime occurredAt) {
    }
}
