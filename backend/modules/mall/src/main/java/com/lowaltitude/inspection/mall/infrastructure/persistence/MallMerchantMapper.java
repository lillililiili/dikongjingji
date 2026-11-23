package com.lowaltitude.inspection.mall.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 商家资料与商家主页（S5-2，迁移 V005）。商家只读写自己机构的资料；运营端按提交单审核。“当前时间”由调用方传入。
 */
public interface MallMerchantMapper {

    // ---- 商家主页 ----

    @Select("SELECT organization_name FROM identity_organization WHERE id = #{organizationId}")
    String findOrganizationName(@Param("organizationId") UUID organizationId);

    /** 这家商家的有效供给：有几种商家类型（取最多的那种）、在哪些城市（null 是全国）。 */
    @Select("""
            SELECT offer.merchant_kind, offer.city_code, count(*) total
            FROM mall_offer offer
            JOIN mall_sku sku ON sku.id = offer.sku_id AND sku.status = 'ACTIVE'
            JOIN mall_product product ON product.id = sku.product_id AND product.status = 'PUBLISHED'
            WHERE offer.merchant_id = #{organizationId}
              AND offer.status = 'ACTIVE'
            GROUP BY offer.merchant_kind, offer.city_code
            ORDER BY total DESC, offer.merchant_kind
            """)
    List<OfferSpreadRow> findOfferSpread(@Param("organizationId") UUID organizationId);

    String PROFILE_COLUMNS = """
            SELECT p.organization_id, p.business_entity, p.intro, p.sale_scope_note, p.authorization_until,
                   p.category_note, p.invoice_issuer, p.aftersale_party, p.approved_at, p.version
            FROM mall_merchant_profile p
            """;

    @Select(PROFILE_COLUMNS + "WHERE p.organization_id = #{organizationId}")
    ProfileRow findProfile(@Param("organizationId") UUID organizationId);

    // ---- 提交单 ----

    String SUBMISSION_COLUMNS = """
            SELECT s.id, s.organization_id, o.organization_name, s.business_entity, s.intro, s.sale_scope_note,
                   s.authorization_until, s.category_note, s.invoice_issuer, s.aftersale_party, s.status,
                   s.submitted_by_name, s.submitted_at, s.reviewed_by_name, s.reviewed_at, s.reject_reason, s.version
            FROM mall_merchant_profile_submission s
            JOIN identity_organization o ON o.id = s.organization_id
            """;

    @Select(SUBMISSION_COLUMNS + """
            WHERE s.organization_id = #{organizationId}
            ORDER BY s.submitted_at DESC, s.id DESC
            LIMIT 1
            """)
    SubmissionRow findLatestSubmission(@Param("organizationId") UUID organizationId);

    @Select(SUBMISSION_COLUMNS + "WHERE s.id = #{id}")
    SubmissionRow findSubmission(@Param("id") UUID id);

    @Select(SUBMISSION_COLUMNS + "WHERE s.id = #{id} FOR UPDATE OF s")
    SubmissionRow lockSubmission(@Param("id") UUID id);

    /** 同一家的提交排队（事务级咨询锁）。 */
    @Select("""
            SELECT 1 FROM (
                SELECT pg_advisory_xact_lock(hashtextextended('mall-merchant-profile:' || CAST(#{organizationId} AS text), 0))
            ) locked
            """)
    Integer lockOrganization(@Param("organizationId") UUID organizationId);

    @Update("""
            UPDATE mall_merchant_profile_submission
            SET status = 'SUPERSEDED', version = version + 1, updated_at = #{at}
            WHERE organization_id = #{organizationId} AND status = 'SUBMITTED'
            """)
    int supersedePending(@Param("organizationId") UUID organizationId, @Param("at") Instant at);

    @Select("""
            INSERT INTO mall_merchant_profile_submission (
                organization_id, business_entity, intro, sale_scope_note, authorization_until, category_note,
                invoice_issuer, aftersale_party, status, submitted_by, submitted_by_name, submitted_at,
                created_at, updated_at
            )
            VALUES (
                #{organizationId}, #{businessEntity}, #{intro}, #{saleScopeNote}, #{authorizationUntil},
                #{categoryNote}, #{invoiceIssuer}, #{aftersaleParty}, 'SUBMITTED', #{submittedBy},
                #{submittedByName}, #{at}, #{at}, #{at}
            )
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertSubmission(
            @Param("organizationId") UUID organizationId,
            @Param("businessEntity") String businessEntity,
            @Param("intro") String intro,
            @Param("saleScopeNote") String saleScopeNote,
            @Param("authorizationUntil") LocalDate authorizationUntil,
            @Param("categoryNote") String categoryNote,
            @Param("invoiceIssuer") String invoiceIssuer,
            @Param("aftersaleParty") String aftersaleParty,
            @Param("submittedBy") UUID submittedBy,
            @Param("submittedByName") String submittedByName,
            @Param("at") Instant at
    );

    String SUBMISSION_FILTER = """
            <where>
              <if test="status != null">s.status = #{status}</if>
              <if test="keyword != null">
                AND (o.organization_name ILIKE #{keyword} ESCAPE '\\' OR s.business_entity ILIKE #{keyword} ESCAPE '\\')
              </if>
            </where>
            """;

    @Select("<script>SELECT count(*) FROM mall_merchant_profile_submission s "
            + "JOIN identity_organization o ON o.id = s.organization_id " + SUBMISSION_FILTER + "</script>")
    long countSubmissions(@Param("status") String status, @Param("keyword") String keyword);

    /** 待审核的在前，再按提交时间新的在前。 */
    @Select("<script>" + SUBMISSION_COLUMNS + SUBMISSION_FILTER + """
            ORDER BY CASE WHEN s.status = 'SUBMITTED' THEN 0 ELSE 1 END, s.submitted_at DESC, s.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<SubmissionRow> findSubmissions(
            @Param("status") String status,
            @Param("keyword") String keyword,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select(SUBMISSION_COLUMNS + """
            WHERE s.organization_id = #{organizationId}
            ORDER BY s.submitted_at DESC, s.id DESC
            LIMIT 20
            """)
    List<SubmissionRow> findHistory(@Param("organizationId") UUID organizationId);

    @Update("""
            UPDATE mall_merchant_profile_submission
            SET status = #{status},
                reviewed_by = #{reviewerId},
                reviewed_by_name = #{reviewerName},
                reviewed_at = #{at},
                reject_reason = #{rejectReason},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{id} AND status = 'SUBMITTED' AND version = #{version}
            """)
    int review(
            @Param("id") UUID id,
            @Param("version") int version,
            @Param("status") String status,
            @Param("reviewerId") UUID reviewerId,
            @Param("reviewerName") String reviewerName,
            @Param("rejectReason") String rejectReason,
            @Param("at") Instant at
    );

    /** 审核通过：把这一版整份写成对外展示的资料。 */
    @Update("""
            INSERT INTO mall_merchant_profile (
                organization_id, business_entity, intro, sale_scope_note, authorization_until, category_note,
                invoice_issuer, aftersale_party, submission_id, approved_at, approved_by, created_at, updated_at
            )
            SELECT s.organization_id, s.business_entity, s.intro, s.sale_scope_note, s.authorization_until,
                   s.category_note, s.invoice_issuer, s.aftersale_party, s.id, #{at}, #{reviewerId}, #{at}, #{at}
            FROM mall_merchant_profile_submission s
            WHERE s.id = #{submissionId}
            ON CONFLICT (organization_id) DO UPDATE
            SET business_entity = EXCLUDED.business_entity,
                intro = EXCLUDED.intro,
                sale_scope_note = EXCLUDED.sale_scope_note,
                authorization_until = EXCLUDED.authorization_until,
                category_note = EXCLUDED.category_note,
                invoice_issuer = EXCLUDED.invoice_issuer,
                aftersale_party = EXCLUDED.aftersale_party,
                submission_id = EXCLUDED.submission_id,
                approved_at = EXCLUDED.approved_at,
                approved_by = EXCLUDED.approved_by,
                version = mall_merchant_profile.version + 1,
                updated_at = EXCLUDED.updated_at
            """)
    int publish(@Param("submissionId") UUID submissionId, @Param("reviewerId") UUID reviewerId, @Param("at") Instant at);

    // ---- 运营端：商品的交易条款（S4-4） ----

    @Select("SELECT id, name, no_reason_return FROM mall_product WHERE id = #{productId} FOR UPDATE")
    ProductTermsRow lockProductTerms(@Param("productId") UUID productId);

    @Select("SELECT id, name, no_reason_return FROM mall_product WHERE id = #{productId}")
    ProductTermsRow findProductTerms(@Param("productId") UUID productId);

    @Update("""
            UPDATE mall_product
            SET no_reason_return = #{value}, version = version + 1, updated_at = #{at}
            WHERE id = #{productId}
            """)
    int updateNoReasonReturn(@Param("productId") UUID productId, @Param("value") boolean value,
                             @Param("at") Instant at);

    @Select("""
            SELECT offer.id offer_id, sku.name sku_name, offer.merchant_id, merchant.organization_name merchant_name,
                   offer.city_code, offer.status, offer.invoice_issuer, offer.aftersale_party,
                   profile.invoice_issuer profile_invoice_issuer, profile.aftersale_party profile_aftersale_party
            FROM mall_offer offer
            JOIN mall_sku sku ON sku.id = offer.sku_id
            JOIN identity_organization merchant ON merchant.id = offer.merchant_id
            LEFT JOIN mall_merchant_profile profile ON profile.organization_id = offer.merchant_id
            WHERE sku.product_id = #{productId}
            ORDER BY sku.sort_order, merchant.organization_name, offer.id
            """)
    List<OfferTermsRow> findOfferTerms(@Param("productId") UUID productId);

    @Select("""
            SELECT offer.id
            FROM mall_offer offer
            JOIN mall_sku sku ON sku.id = offer.sku_id
            WHERE offer.id = #{offerId} AND sku.product_id = #{productId}
            FOR UPDATE OF offer
            """)
    UUID lockOffer(@Param("productId") UUID productId, @Param("offerId") UUID offerId);

    @Update("""
            UPDATE mall_offer
            SET invoice_issuer = #{invoiceIssuer},
                aftersale_party = #{aftersaleParty},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{offerId}
            """)
    int updateOfferTerms(@Param("offerId") UUID offerId, @Param("invoiceIssuer") String invoiceIssuer,
                         @Param("aftersaleParty") String aftersaleParty, @Param("at") Instant at);

    /** 和“商品与供给”同一张留痕表（mall_catalog_operation），运营端商品详情的操作记录里能看到。 */
    @Update("""
            INSERT INTO mall_catalog_operation (product_id, action, detail, operator_id, operator_name, occurred_at)
            VALUES (#{productId}, #{action}, #{detail}, #{operatorId}, #{operatorName}, #{at})
            """)
    int insertCatalogOperation(@Param("productId") UUID productId, @Param("action") String action,
                               @Param("detail") String detail, @Param("operatorId") UUID operatorId,
                               @Param("operatorName") String operatorName, @Param("at") Instant at);

    record ProductTermsRow(UUID id, String name, boolean noReasonReturn) {
    }

    record OfferTermsRow(UUID offerId, String skuName, UUID merchantId, String merchantName, String cityCode,
                         String status, String invoiceIssuer, String aftersaleParty, String profileInvoiceIssuer,
                         String profileAftersaleParty) {
    }

    record OfferSpreadRow(String merchantKind, String cityCode, long total) {
    }

    record ProfileRow(
            UUID organizationId,
            String businessEntity,
            String intro,
            String saleScopeNote,
            LocalDate authorizationUntil,
            String categoryNote,
            String invoiceIssuer,
            String aftersaleParty,
            Instant approvedAt,
            int version
    ) {
    }

    record SubmissionRow(
            UUID id,
            UUID organizationId,
            String organizationName,
            String businessEntity,
            String intro,
            String saleScopeNote,
            LocalDate authorizationUntil,
            String categoryNote,
            String invoiceIssuer,
            String aftersaleParty,
            String status,
            String submittedByName,
            Instant submittedAt,
            String reviewedByName,
            Instant reviewedAt,
            String rejectReason,
            int version
    ) {
    }
}
