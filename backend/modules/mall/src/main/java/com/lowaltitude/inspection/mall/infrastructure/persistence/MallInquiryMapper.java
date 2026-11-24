package com.lowaltitude.inspection.mall.infrastructure.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 询价单、邀请、报价的读写（F4）。全部用绑定参数；客户的读写以账号为条件，商家的读写以“这家被邀请了”为条件。
 * “当前时间”由调用方传入。加锁顺序：询价单 → 邀请 → 报价。
 */
public interface MallInquiryMapper {

    // ---- 询价供给 ----

    /** 商家被挂起上架（身份迁移 V013）的供给当作已停用，不能再发询价。 */
    String OFFER_COLUMNS = """
            SELECT offer.id offer_id,
                   p.id product_id,
                   p.name product_name,
                   p.cover_url,
                   p.sale_mode,
                   p.status product_status,
                   sku.status sku_status,
                   CASE WHEN merchant.listing_suspended THEN 'DISABLED' ELSE offer.status END offer_status,
                   offer.city_code,
                   merchant.id merchant_id,
                   merchant.organization_name merchant_name,
                   offer.merchant_kind,
                   offer.delivery_note
            FROM mall_offer offer
            JOIN mall_sku sku ON sku.id = offer.sku_id
            JOIN mall_product p ON p.id = sku.product_id
            JOIN identity_organization merchant ON merchant.id = offer.merchant_id
            """;

    /** 这件询价商品在这个城市的在售供给（全国供给也算），按商家名排。 */
    @Select(OFFER_COLUMNS + """
            WHERE p.id = #{productId}
              AND p.status = 'PUBLISHED'
              AND p.sale_mode = 'INQUIRY'
              AND sku.status = 'ACTIVE'
              AND offer.status = 'ACTIVE'
              AND NOT merchant.listing_suspended
              AND (offer.city_code IS NULL OR offer.city_code = #{cityCode})
            ORDER BY merchant.organization_name ASC, offer.id ASC
            """)
    List<OfferRow> findInquiryOffers(@Param("productId") UUID productId, @Param("cityCode") String cityCode);

    @Select("<script>" + OFFER_COLUMNS + """
            WHERE offer.id IN
            <foreach collection="offerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<OfferRow> findOffers(@Param("offerIds") List<UUID> offerIds);

    // ---- 询价单 ----

    @Select("""
            SELECT 1 FROM (
                SELECT pg_advisory_xact_lock(hashtextextended('mall-inquiry:' || CAST(#{accountId} AS text), 0))
            ) locked
            """)
    Integer lockAccount(@Param("accountId") UUID accountId);

    @Select("""
            SELECT id, request_hash
            FROM mall_inquiry
            WHERE account_id = #{accountId}
              AND idempotency_key = #{idempotencyKey}
            """)
    KeyRow findByKey(@Param("accountId") UUID accountId, @Param("idempotencyKey") UUID idempotencyKey);

    @Insert("""
            INSERT INTO mall_inquiry (
                id, account_id, buyer_kind, buyer_organization_id, buyer_organization_name, buyer_contact_name,
                product_id, product_name, cover_url, requirement, quantity, city_code, expected_date, quote_deadline,
                idempotency_key, request_hash, created_at, updated_at
            )
            VALUES (
                #{id}, #{accountId}, #{buyerKind}, #{buyerOrganizationId}, #{buyerOrganizationName}, #{buyerContactName},
                #{productId}, #{productName}, #{coverUrl}, #{requirement}, #{quantity}, #{cityCode}, #{expectedDate},
                #{quoteDeadline}, #{idempotencyKey}, #{requestHash}, #{at}, #{at}
            )
            """)
    int insertInquiry(
            @Param("id") UUID id,
            @Param("accountId") UUID accountId,
            @Param("buyerKind") String buyerKind,
            @Param("buyerOrganizationId") UUID buyerOrganizationId,
            @Param("buyerOrganizationName") String buyerOrganizationName,
            @Param("buyerContactName") String buyerContactName,
            @Param("productId") UUID productId,
            @Param("productName") String productName,
            @Param("coverUrl") String coverUrl,
            @Param("requirement") String requirement,
            @Param("quantity") int quantity,
            @Param("cityCode") String cityCode,
            @Param("expectedDate") LocalDate expectedDate,
            @Param("quoteDeadline") OffsetDateTime quoteDeadline,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("at") OffsetDateTime at
    );

    @Insert("""
            INSERT INTO mall_inquiry_invitation (
                inquiry_id, offer_id, merchant_id, merchant_name, merchant_kind, sort_order, created_at, updated_at
            )
            VALUES (#{inquiryId}, #{offerId}, #{merchantId}, #{merchantName}, #{merchantKind}, #{sortOrder}, #{at}, #{at})
            """)
    int insertInvitation(
            @Param("inquiryId") UUID inquiryId,
            @Param("offerId") UUID offerId,
            @Param("merchantId") UUID merchantId,
            @Param("merchantName") String merchantName,
            @Param("merchantKind") String merchantKind,
            @Param("sortOrder") int sortOrder,
            @Param("at") OffsetDateTime at
    );

    String INQUIRY_COLUMNS = """
            SELECT i.id,
                   i.account_id,
                   i.buyer_kind,
                   i.buyer_organization_name,
                   i.buyer_contact_name,
                   i.product_id,
                   i.product_name,
                   i.cover_url,
                   i.requirement,
                   i.quantity,
                   i.city_code,
                   i.expected_date,
                   i.status,
                   i.close_reason,
                   i.quote_deadline,
                   i.chosen_quote_id,
                   i.chosen_at,
                   i.created_at,
                   i.updated_at,
                   i.version
            FROM mall_inquiry i
            """;

    @Select(INQUIRY_COLUMNS + "WHERE i.id = #{inquiryId} AND i.account_id = #{accountId}")
    InquiryRow findInquiry(@Param("accountId") UUID accountId, @Param("inquiryId") UUID inquiryId);

    @Select(INQUIRY_COLUMNS + "WHERE i.id = #{inquiryId} AND i.account_id = #{accountId} FOR UPDATE")
    InquiryRow lockInquiry(@Param("accountId") UUID accountId, @Param("inquiryId") UUID inquiryId);

    String ACCOUNT_INQUIRIES = """
            WHERE i.account_id = #{accountId}
              AND (CAST(#{status} AS varchar) IS NULL OR i.status = #{status})
            """;

    @Select("SELECT count(*) FROM mall_inquiry i " + ACCOUNT_INQUIRIES)
    long countInquiries(@Param("accountId") UUID accountId, @Param("status") String status);

    @Select(INQUIRY_COLUMNS + ACCOUNT_INQUIRIES + """
            ORDER BY i.updated_at DESC, i.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<InquiryRow> findInquiryPage(
            @Param("accountId") UUID accountId,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /** 商家：被邀请了的询价单（不论这家的邀请状态）。 */
    String INVITED = """
            EXISTS (SELECT 1 FROM mall_inquiry_invitation v WHERE v.inquiry_id = i.id AND v.merchant_id = #{merchantId})
            """;

    @Select(INQUIRY_COLUMNS + "WHERE i.id = #{inquiryId} AND " + INVITED)
    InquiryRow findMerchantInquiry(@Param("merchantId") UUID merchantId, @Param("inquiryId") UUID inquiryId);

    @Select(INQUIRY_COLUMNS + "WHERE i.id = #{inquiryId} AND " + INVITED + " FOR UPDATE OF i")
    InquiryRow lockMerchantInquiry(@Param("merchantId") UUID merchantId, @Param("inquiryId") UUID inquiryId);

    String MERCHANT_INQUIRIES = "WHERE " + INVITED + """
              AND (CAST(#{status} AS varchar) IS NULL OR i.status = #{status})
            """;

    @Select("SELECT count(*) FROM mall_inquiry i " + MERCHANT_INQUIRIES)
    long countMerchantInquiries(@Param("merchantId") UUID merchantId, @Param("status") String status);

    @Select(INQUIRY_COLUMNS + MERCHANT_INQUIRIES + """
            ORDER BY i.created_at DESC, i.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<InquiryRow> findMerchantInquiryPage(
            @Param("merchantId") UUID merchantId,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select("""
            <script>
            SELECT inquiry_id, offer_id, merchant_id, merchant_name, merchant_kind, status, decline_reason
            FROM mall_inquiry_invitation
            WHERE inquiry_id IN
            <foreach collection="inquiryIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY inquiry_id, sort_order, offer_id
            </script>
            """)
    List<InvitationRow> findInvitations(@Param("inquiryIds") List<UUID> inquiryIds);

    @Select("""
            SELECT inquiry_id, offer_id, merchant_id, merchant_name, merchant_kind, status, decline_reason
            FROM mall_inquiry_invitation
            WHERE inquiry_id = #{inquiryId}
              AND merchant_id = #{merchantId}
            FOR UPDATE
            """)
    InvitationRow lockInvitation(@Param("inquiryId") UUID inquiryId, @Param("merchantId") UUID merchantId);

    @Update("""
            UPDATE mall_inquiry_invitation
            SET status = #{status},
                decline_reason = #{reason},
                declined_at = CASE WHEN #{status} = 'DECLINED' THEN CAST(#{at} AS timestamptz) END,
                version = version + 1,
                updated_at = #{at}
            WHERE inquiry_id = #{inquiryId}
              AND merchant_id = #{merchantId}
              AND status <> 'DECLINED'
            """)
    int markInvitation(
            @Param("inquiryId") UUID inquiryId,
            @Param("merchantId") UUID merchantId,
            @Param("status") String status,
            @Param("reason") String reason,
            @Param("at") OffsetDateTime at
    );

    /** 待报价 ↔ 报价中：有没有有效报价变了。 */
    @Update("""
            UPDATE mall_inquiry
            SET status = #{toStatus},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{inquiryId}
              AND status = #{fromStatus}
            """)
    int changeStatus(
            @Param("inquiryId") UUID inquiryId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("at") OffsetDateTime at
    );

    /** 选定：只改还在待报价、报价中、版本对得上的那一份。 */
    @Update("""
            UPDATE mall_inquiry
            SET status = 'CHOSEN',
                chosen_quote_id = #{quoteId},
                chosen_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{inquiryId}
              AND status IN ('OPEN', 'QUOTED')
              AND version = #{version}
            """)
    int choose(
            @Param("inquiryId") UUID inquiryId,
            @Param("quoteId") UUID quoteId,
            @Param("version") int version,
            @Param("at") OffsetDateTime at
    );

    @Update("""
            UPDATE mall_inquiry
            SET status = 'CLOSED',
                close_reason = #{reason},
                closed_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{inquiryId}
              AND status IN ('OPEN', 'QUOTED')
            """)
    int close(@Param("inquiryId") UUID inquiryId, @Param("reason") String reason, @Param("at") OffsetDateTime at);

    /** 定时任务：过了截止还在待报价的、或有效报价过了有效期的，跳过别的实例正在处理的行。 */
    @Select(INQUIRY_COLUMNS + """
            WHERE (i.status = 'OPEN' AND i.quote_deadline <= #{now})
               OR (i.status = 'QUOTED' AND EXISTS (
                      SELECT 1 FROM mall_inquiry_quote q
                      WHERE q.inquiry_id = i.id AND q.status = 'ACTIVE' AND q.valid_until <= #{now}))
            ORDER BY i.quote_deadline ASC, i.id ASC
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<InquiryRow> lockDue(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    // ---- 报价 ----

    String QUOTE_COLUMNS = """
            SELECT q.id,
                   q.inquiry_id,
                   q.offer_id,
                   q.merchant_id,
                   q.quote_version,
                   q.status,
                   q.unit_amount_minor,
                   q.quantity,
                   q.total_minor,
                   q.model_text,
                   q.lead_days,
                   q.includes_text,
                   q.valid_until,
                   q.quoted_at,
                   q.last_order_id
            FROM mall_inquiry_quote q
            """;

    /** 每家最新的一版（被取代的旧版不算）。 */
    @Select("<script>" + QUOTE_COLUMNS + """
            WHERE q.inquiry_id IN
            <foreach collection="inquiryIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              AND q.status IN ('ACTIVE', 'EXPIRED', 'WITHDRAWN', 'CHOSEN', 'LAPSED')
            ORDER BY q.inquiry_id, q.quoted_at ASC, q.id ASC
            </script>
            """)
    List<QuoteRow> findQuotes(@Param("inquiryIds") List<UUID> inquiryIds);

    @Select(QUOTE_COLUMNS + "WHERE q.id = #{quoteId} AND q.inquiry_id = #{inquiryId}")
    QuoteRow findQuote(@Param("inquiryId") UUID inquiryId, @Param("quoteId") UUID quoteId);

    /** 这家最新的一版报价（含已撤回、已过期）；还没报过时为 null。 */
    @Select(QUOTE_COLUMNS + """
            WHERE q.inquiry_id = #{inquiryId}
              AND q.merchant_id = #{merchantId}
            ORDER BY q.quote_version DESC
            LIMIT 1
            FOR UPDATE
            """)
    QuoteRow lockLatestQuote(@Param("inquiryId") UUID inquiryId, @Param("merchantId") UUID merchantId);

    /** 改价前把这家有效、已过期的旧版标为被取代。 */
    @Update("""
            UPDATE mall_inquiry_quote
            SET status = 'SUPERSEDED',
                version = version + 1,
                updated_at = #{at}
            WHERE inquiry_id = #{inquiryId}
              AND merchant_id = #{merchantId}
              AND status IN ('ACTIVE', 'EXPIRED')
            """)
    int supersede(@Param("inquiryId") UUID inquiryId, @Param("merchantId") UUID merchantId,
                  @Param("at") OffsetDateTime at);

    @Select("""
            INSERT INTO mall_inquiry_quote (
                inquiry_id, offer_id, merchant_id, quote_version, unit_amount_minor, quantity, total_minor, model_text,
                lead_days, includes_text, valid_until, quoted_by, quoted_at, created_at, updated_at
            )
            VALUES (
                #{inquiryId}, #{offerId}, #{merchantId}, #{quoteVersion}, #{unitAmountMinor}, #{quantity}, #{totalMinor},
                #{model}, #{leadDays}, #{includes}, #{validUntil}, #{quotedBy}, #{at}, #{at}, #{at}
            )
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertQuote(
            @Param("inquiryId") UUID inquiryId,
            @Param("offerId") UUID offerId,
            @Param("merchantId") UUID merchantId,
            @Param("quoteVersion") int quoteVersion,
            @Param("unitAmountMinor") long unitAmountMinor,
            @Param("quantity") int quantity,
            @Param("totalMinor") long totalMinor,
            @Param("model") String model,
            @Param("leadDays") int leadDays,
            @Param("includes") String includes,
            @Param("validUntil") OffsetDateTime validUntil,
            @Param("quotedBy") UUID quotedBy,
            @Param("at") OffsetDateTime at
    );

    /** 商家拒绝时撤回这家的有效报价。 */
    @Update("""
            UPDATE mall_inquiry_quote
            SET status = 'WITHDRAWN',
                version = version + 1,
                updated_at = #{at}
            WHERE inquiry_id = #{inquiryId}
              AND merchant_id = #{merchantId}
              AND status = 'ACTIVE'
            """)
    int withdraw(@Param("inquiryId") UUID inquiryId, @Param("merchantId") UUID merchantId,
                 @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE mall_inquiry_quote
            SET status = 'CHOSEN',
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{quoteId}
              AND inquiry_id = #{inquiryId}
              AND status = 'ACTIVE'
            """)
    int markChosen(@Param("inquiryId") UUID inquiryId, @Param("quoteId") UUID quoteId, @Param("at") OffsetDateTime at);

    /** 选定或关闭后，其余有效的报价失效。 */
    @Update("""
            UPDATE mall_inquiry_quote
            SET status = 'LAPSED',
                version = version + 1,
                updated_at = #{at}
            WHERE inquiry_id = #{inquiryId}
              AND status = 'ACTIVE'
            """)
    int lapseActive(@Param("inquiryId") UUID inquiryId, @Param("at") OffsetDateTime at);

    /** 过了有效期的有效报价标为过期，返回它们的 ID。 */
    @Select("""
            UPDATE mall_inquiry_quote
            SET status = 'EXPIRED',
                version = version + 1,
                updated_at = #{now}
            WHERE inquiry_id = #{inquiryId}
              AND status = 'ACTIVE'
              AND valid_until <= #{now}
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    List<UUID> expireQuotes(@Param("inquiryId") UUID inquiryId, @Param("now") OffsetDateTime now);

    @Select("SELECT count(*) FROM mall_inquiry_quote WHERE inquiry_id = #{inquiryId} AND status = 'ACTIVE'")
    int countActiveQuotes(@Param("inquiryId") UUID inquiryId);

    @Select("SELECT count(*) FROM mall_inquiry_quote WHERE inquiry_id = #{inquiryId}")
    int countQuotes(@Param("inquiryId") UUID inquiryId);

    // ---- 下单 ----

    /** 这个账号的一份报价和它所在询价单的状态；不是这个账号的询价单时为 null。 */
    @Select("""
            SELECT q.id quote_id,
                   q.offer_id,
                   q.status quote_status,
                   q.unit_amount_minor,
                   q.quantity,
                   q.model_text,
                   q.valid_until,
                   q.last_order_id,
                   i.id inquiry_id,
                   i.status inquiry_status,
                   i.chosen_quote_id
            FROM mall_inquiry_quote q
            JOIN mall_inquiry i ON i.id = q.inquiry_id
            WHERE q.id = #{quoteId}
              AND i.account_id = #{accountId}
            """)
    OrderQuoteRow findOrderQuote(@Param("accountId") UUID accountId, @Param("quoteId") UUID quoteId);

    /** 下单后在选中的报价上记下这张订单。 */
    @Update("""
            UPDATE mall_inquiry_quote
            SET last_order_id = #{orderId},
                last_ordered_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{quoteId}
              AND status = 'CHOSEN'
            """)
    int recordOrder(@Param("quoteId") UUID quoteId, @Param("orderId") UUID orderId, @Param("at") OffsetDateTime at);

    // ---- 记录 ----

    @Insert("""
            INSERT INTO mall_inquiry_event (inquiry_id, quote_id, event_type, actor_id, order_id, occurred_at)
            VALUES (#{inquiryId}, #{quoteId}, #{eventType}, #{actorId}, #{orderId}, #{at})
            """)
    int insertEvent(
            @Param("inquiryId") UUID inquiryId,
            @Param("quoteId") UUID quoteId,
            @Param("eventType") String eventType,
            @Param("actorId") UUID actorId,
            @Param("orderId") UUID orderId,
            @Param("at") OffsetDateTime at
    );

    // ---- 行 ----

    record KeyRow(UUID id, String requestHash) {
    }

    record OfferRow(
            UUID offerId,
            UUID productId,
            String productName,
            String coverUrl,
            String saleMode,
            String productStatus,
            String skuStatus,
            String offerStatus,
            String cityCode,
            UUID merchantId,
            String merchantName,
            String merchantKind,
            String deliveryNote
    ) {
    }

    record InquiryRow(
            UUID id,
            UUID accountId,
            String buyerKind,
            String buyerOrganizationName,
            String buyerContactName,
            UUID productId,
            String productName,
            String coverUrl,
            String requirement,
            int quantity,
            String cityCode,
            LocalDate expectedDate,
            String status,
            String closeReason,
            OffsetDateTime quoteDeadline,
            UUID chosenQuoteId,
            OffsetDateTime chosenAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version
    ) {
    }

    record InvitationRow(
            UUID inquiryId,
            UUID offerId,
            UUID merchantId,
            String merchantName,
            String merchantKind,
            String status,
            String declineReason
    ) {
    }

    record QuoteRow(
            UUID id,
            UUID inquiryId,
            UUID offerId,
            UUID merchantId,
            int quoteVersion,
            String status,
            long unitAmountMinor,
            int quantity,
            long totalMinor,
            String modelText,
            int leadDays,
            String includesText,
            OffsetDateTime validUntil,
            OffsetDateTime quotedAt,
            UUID lastOrderId
    ) {
    }

    record OrderQuoteRow(
            UUID quoteId,
            UUID offerId,
            String quoteStatus,
            long unitAmountMinor,
            int quantity,
            String modelText,
            OffsetDateTime validUntil,
            UUID lastOrderId,
            UUID inquiryId,
            String inquiryStatus,
            UUID chosenQuoteId
    ) {
    }
}
