package com.lowaltitude.inspection.infrastructure.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface InspectionServiceCatalogMapper {

    String CARD_COLUMNS = """
            SELECT service.id service_id,
                   service.service_no,
                   service.category_code,
                   service.category_name,
                   service.title name,
                   service.summary,
                   service.cover_url,
                   service.price_mode,
                   service.starting_amount_minor,
                   service.currency,
                   service.unit_code,
                   service.unit_label,
                   service.price_note,
                   service.availability,
                   service.primary_action,
                   service.completed_count,
            """;

    String FROM_SERVICE = """
            FROM inspection_service service
            LEFT JOIN inspection_category category
              ON category.code = service.category_code
            """;

    /** A service a customer in #{cityCode} can see and book. Every list and count goes through it. */
    String VISIBLE_IN_CITY = """
            WHERE service.status = 'ACTIVE'
              AND service.service_no IS NOT NULL
              AND service.category_code IS NOT NULL
              AND service.category_name IS NOT NULL
              AND service.summary IS NOT NULL
              AND service.price_mode IS NOT NULL
              AND service.currency IS NOT NULL
              AND service.availability IS NOT NULL
              AND service.primary_action IS NOT NULL
              AND (
                  service.price_mode = 'PROJECT_QUOTE'
                  OR service.starting_amount_minor IS NOT NULL
              )
              AND EXISTS (
                  SELECT 1
                  FROM inspection_service_city coverage
                  WHERE coverage.service_id = service.id
                    AND coverage.city_code = #{cityCode}
                    AND coverage.status = 'ACTIVE'
              )
              AND EXISTS (
                  SELECT 1
                  FROM inspection_service_booking_item item
                  WHERE item.service_id = service.id
                    AND item.status = 'ACTIVE'
              )
            """;

    /**
     * The customer's conditions. A top-level category includes its children (变电站 under 电力).
     * Every value is bound; the lists are whitelisted by the read service before they get here.
     */
    String MATCHING_FILTERS = """
              AND (
                  #{categoryCode} = 'ALL'
                  OR service.category_code = #{categoryCode}
                  OR category.parent_code = #{categoryCode}
              )
            <if test="keyword != null">
              AND (
                  service.title ILIKE #{keyword} ESCAPE '\\'
                  OR service.summary ILIKE #{keyword} ESCAPE '\\'
                  OR service.category_name ILIKE #{keyword} ESCAPE '\\'
                  OR EXISTS (
                      SELECT 1
                      FROM inspection_service_booking_item searched
                      WHERE searched.service_id = service.id
                        AND searched.status = 'ACTIVE'
                        AND searched.item_label ILIKE #{keyword} ESCAPE '\\'
                  )
              )
            </if>
            <if test="serviceMethods != null and !serviceMethods.isEmpty()">
              AND EXISTS (
                  SELECT 1
                  FROM inspection_service_method method
                  WHERE method.service_id = service.id
                    AND method.method_code IN
                    <foreach collection="serviceMethods" item="methodCode"
                             open="(" separator="," close=")">#{methodCode}</foreach>
              )
            </if>
            <if test="availability != null and !availability.isEmpty()">
              AND service.availability IN
              <foreach collection="availability" item="availabilityCode"
                       open="(" separator="," close=")">#{availabilityCode}</foreach>
            </if>
            <if test="priceModes != null and !priceModes.isEmpty()">
              AND service.price_mode IN
              <foreach collection="priceModes" item="priceMode"
                       open="(" separator="," close=")">#{priceMode}</foreach>
            </if>
            <if test="includesReport != null and includesReport">
              AND service.includes_report
            </if>
            """;

    @Select("<script>SELECT count(*)\n" + FROM_SERVICE + VISIBLE_IN_CITY + MATCHING_FILTERS
            + "</script>")
    long countMatching(
            @Param("cityCode") String cityCode,
            @Param("categoryCode") String categoryCode,
            @Param("keyword") String keyword,
            @Param("serviceMethods") List<String> serviceMethods,
            @Param("availability") List<String> availability,
            @Param("priceModes") List<String> priceModes,
            @Param("includesReport") Boolean includesReport
    );

    /**
     * One page of matching services. The distance is from the customer to the service's centre
     * point in this city (inspection_service_coverage); without a position, or without a centre
     * point, it is NULL and sorts last.
     */
    @Select("<script>" + CARD_COLUMNS + """
            <choose>
              <when test="latitude != null and longitude != null">
                   round(ST_Distance(
                       center.center_point,
                       ST_SetSRID(ST_MakePoint(#{longitude}, #{latitude}), 4326)::geography
                   )) distance_meters
              </when>
              <otherwise>
                   NULL::double precision distance_meters
              </otherwise>
            </choose>
            """ + FROM_SERVICE + """
            LEFT JOIN inspection_service_coverage center
              ON center.service_id = service.id
             AND center.city_code = #{cityCode}
            """ + VISIBLE_IN_CITY + MATCHING_FILTERS + """
            ORDER BY
            <if test="sort == 'DISTANCE_ASC'">
              distance_meters ASC NULLS LAST,
            </if>
              CASE WHEN #{sort} = 'COMPREHENSIVE'
                THEN COALESCE(category.sort_order, 9999) END ASC,
              CASE WHEN #{sort} IN ('COMPREHENSIVE', 'SALES_DESC')
                THEN service.completed_count END DESC,
              CASE WHEN #{sort} IN ('PRICE_ASC', 'PRICE_DESC')
                AND service.price_mode = 'PROJECT_QUOTE' THEN 1 ELSE 0 END ASC,
              CASE WHEN #{sort} = 'PRICE_ASC'
                THEN service.starting_amount_minor END ASC NULLS LAST,
              CASE WHEN #{sort} = 'PRICE_DESC'
                THEN service.starting_amount_minor END DESC NULLS LAST,
              service.id ASC
            LIMIT #{limit}
            OFFSET #{offset}
            </script>
            """)
    List<ServiceCardRow> findMatchingPage(
            @Param("cityCode") String cityCode,
            @Param("categoryCode") String categoryCode,
            @Param("keyword") String keyword,
            @Param("serviceMethods") List<String> serviceMethods,
            @Param("availability") List<String> availability,
            @Param("priceModes") List<String> priceModes,
            @Param("includesReport") Boolean includesReport,
            @Param("latitude") Double latitude,
            @Param("longitude") Double longitude,
            @Param("sort") String sort,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select("SELECT EXISTS (SELECT 1\n" + FROM_SERVICE + VISIBLE_IN_CITY + ")")
    boolean existsVisibleInCity(@Param("cityCode") String cityCode);

    /** The services customers completed most often in the city; the portal shows them as 热门. */
    @Select(CARD_COLUMNS + "NULL::double precision distance_meters\n" + FROM_SERVICE
            + VISIBLE_IN_CITY + """
            ORDER BY service.completed_count DESC, service.id ASC
            LIMIT #{limit}
            """)
    List<ServiceCardRow> findMostCompleted(
            @Param("cityCode") String cityCode,
            @Param("limit") int limit
    );

    @Select("""
            SELECT code, name, subtitle, sort_order
            FROM inspection_category
            WHERE status = 'ACTIVE'
              AND parent_code IS NULL
            ORDER BY sort_order, code
            """)
    List<CategoryRow> findTopLevelCategories();

    /** A city's own rows of a type replace the nationwide default (city_code IS NULL) of that type. */
    @Select("""
            SELECT content.content_type,
                   content.item_code,
                   content.title,
                   content.subtitle,
                   content.highlighted_text,
                   content.image_url,
                   content.sort_order
            FROM inspection_portal_content content
            WHERE content.status = 'ACTIVE'
              AND (
                  content.city_code = #{cityCode}
                  OR (
                      content.city_code IS NULL
                      AND NOT EXISTS (
                          SELECT 1
                          FROM inspection_portal_content own
                          WHERE own.status = 'ACTIVE'
                            AND own.city_code = #{cityCode}
                            AND own.content_type = content.content_type
                      )
                  )
              )
            ORDER BY content.content_type, content.sort_order, content.item_code
            """)
    List<PortalContentRow> findPortalContent(@Param("cityCode") String cityCode);

    @Select("""
            SELECT GREATEST(
                (SELECT max(updated_at) FROM inspection_category),
                (SELECT max(updated_at) FROM inspection_portal_content)
            )
            """)
    OffsetDateTime findCatalogConfigUpdatedAt();

    @Select("""
            SELECT city_code, city_name, province_code
            FROM platform_city
            WHERE city_code = #{cityCode}
              AND status = 'ACTIVE'
            """)
    Optional<ServiceCityRow> findCity(@Param("cityCode") String cityCode);

    @Select("""
            SELECT DISTINCT method.method_code
            FROM inspection_service_method method
            WHERE method.service_id IN (SELECT service.id
            """ + FROM_SERVICE + VISIBLE_IN_CITY + """
            )
            ORDER BY method.method_code
            """)
    List<String> findServiceMethodsInCity(@Param("cityCode") String cityCode);

    @Select("SELECT DISTINCT service.price_mode\n" + FROM_SERVICE + VISIBLE_IN_CITY
            + "ORDER BY service.price_mode")
    List<String> findPriceModesInCity(@Param("cityCode") String cityCode);

    @Select("SELECT EXISTS (SELECT 1\n" + FROM_SERVICE + VISIBLE_IN_CITY
            + "AND service.includes_report)")
    boolean existsReportServiceInCity(@Param("cityCode") String cityCode);

    @Select("""
            SELECT item_code code, item_label name
            FROM inspection_service_booking_item
            WHERE service_id = #{serviceId}
              AND status = 'ACTIVE'
            ORDER BY display_order, id
            """)
    List<FeatureRow> findFeatures(@Param("serviceId") UUID serviceId);

    @Select("""
            SELECT service.id service_id,
                   service.service_no,
                   service.category_code,
                   service.category_name,
                   service.title name,
                   service.summary,
                   service.cover_url,
                   service.price_mode,
                   service.starting_amount_minor,
                   service.currency,
                   service.unit_code,
                   service.unit_label,
                   service.price_note,
                   service.cancellation_note,
                   service.availability,
                   service.primary_action,
                   service.completed_count,
                   service.provider_id,
                   organization.organization_name provider_name,
                   profile.logo_url provider_logo_url,
                   profile.logo_asset_code provider_logo_asset_code,
                   profile.certification_label,
                   organization.qualification_reviewed_at IS NOT NULL provider_qualification_reviewed,
                   profile.team_description,
                   stats.rating_average,
                   stats.review_count,
                   stats.positive_rate
            FROM inspection_service service
            JOIN identity_organization organization
              ON organization.id = service.provider_id
             AND organization.organization_type = 'PROVIDER'
             AND organization.status = 'ACTIVE'
            LEFT JOIN inspection_provider_profile profile
              ON profile.provider_id = service.provider_id
             AND profile.status = 'ACTIVE'
            LEFT JOIN LATERAL (
                SELECT round(avg(review.rating)::numeric, 1)::double precision
                           rating_average,
                       count(*) review_count,
                       round(
                           count(*) FILTER (WHERE review.rating >= 4)
                           * 100.0 / NULLIF(count(*), 0)
                       )::integer positive_rate
                FROM inspection_service_review review
                WHERE review.service_id = service.id
                  AND review.status = 'ACTIVE'
            ) stats ON true
            WHERE service.id = #{serviceId}
              AND service.status = 'ACTIVE'
              AND service.service_no IS NOT NULL
              AND service.category_code IS NOT NULL
              AND service.category_name IS NOT NULL
              AND service.summary IS NOT NULL
              AND service.price_mode IS NOT NULL
              AND service.currency IS NOT NULL
              AND service.availability IS NOT NULL
              AND service.primary_action IS NOT NULL
              AND (
                  service.price_mode = 'PROJECT_QUOTE'
                  OR service.starting_amount_minor IS NOT NULL
              )
              AND EXISTS (
                  SELECT 1
                  FROM inspection_service_city coverage
                  WHERE coverage.service_id = service.id
                    AND coverage.city_code = #{cityCode}
                    AND coverage.status = 'ACTIVE'
              )
            """)
    Optional<ServiceDetailRow> findActiveDetail(
            @Param("serviceId") UUID serviceId,
            @Param("cityCode") String cityCode
    );

    @Select("""
            SELECT item_code code,
                   item_label title,
                   description,
                   icon_key,
                   NULL::varchar image_url,
                   NULL::varchar image_asset_code
            FROM inspection_service_booking_item
            WHERE service_id = #{serviceId}
              AND status = 'ACTIVE'
            ORDER BY display_order, id
            """)
    List<DetailItemRow> findBookingDetailItems(
            @Param("serviceId") UUID serviceId
    );

    @Select("""
            SELECT item_code code,
                   title,
                   description,
                   icon_key,
                   image_url,
                   image_asset_code
            FROM inspection_service_detail_item
            WHERE service_id = #{serviceId}
              AND section_type = #{sectionType}
              AND status = 'ACTIVE'
            ORDER BY display_order, id
            """)
    List<DetailItemRow> findDetailItems(
            @Param("serviceId") UUID serviceId,
            @Param("sectionType") String sectionType
    );

    @Select("""
            SELECT coverage.city_code,
                   city.city_name,
                   city.province_code
            FROM inspection_service_city coverage
            JOIN platform_city city
              ON city.city_code = coverage.city_code
             AND city.status = 'ACTIVE'
            WHERE coverage.service_id = #{serviceId}
              AND coverage.status = 'ACTIVE'
            ORDER BY city.province_code, city.city_pinyin, city.city_code
            """)
    List<ServiceCityRow> findActiveCities(
            @Param("serviceId") UUID serviceId
    );

    @Select("""
            SELECT tag.tag_code code,
                   tag.tag_label label,
                   count(*) tag_count
            FROM inspection_service_review_tag tag
            JOIN inspection_service_review review
              ON review.id = tag.review_id
             AND review.status = 'ACTIVE'
            WHERE review.service_id = #{serviceId}
            GROUP BY tag.tag_code, tag.tag_label
            ORDER BY tag_count DESC, tag.tag_code
            """)
    List<ReviewTagRow> findReviewTags(
            @Param("serviceId") UUID serviceId
    );

    @Select("""
            SELECT id,
                   reviewer_name_snapshot masked_user_name,
                   rating,
                   reviewed_at::date review_date,
                   content
            FROM inspection_service_review
            WHERE service_id = #{serviceId}
              AND status = 'ACTIVE'
            ORDER BY reviewed_at DESC, id
            LIMIT #{limit}
            """)
    List<ReviewRow> findReviewPreview(
            @Param("serviceId") UUID serviceId,
            @Param("limit") int limit
    );

    @Select("""
            SELECT review_id,
                   image_url,
                   image_asset_code
            FROM inspection_service_review_image
            WHERE review_id = #{reviewId}
              AND status = 'ACTIVE'
            ORDER BY display_order, id
            """)
    List<ReviewImageRow> findReviewImages(
            @Param("reviewId") UUID reviewId
    );

    record ServiceCardRow(
            UUID serviceId,
            String serviceNo,
            String categoryCode,
            String categoryName,
            String name,
            String summary,
            String coverUrl,
            String priceMode,
            Long startingAmountMinor,
            String currency,
            String unitCode,
            String unitLabel,
            String priceNote,
            String availability,
            String primaryAction,
            long completedCount,
            Double distanceMeters
    ) {
    }

    record FeatureRow(String code, String name) {
    }

    record CategoryRow(String code, String name, String subtitle, int sortOrder) {
    }

    record PortalContentRow(
            String contentType,
            String itemCode,
            String title,
            String subtitle,
            String highlightedText,
            String imageUrl,
            int sortOrder
    ) {
    }

    record ServiceDetailRow(
            UUID serviceId,
            String serviceNo,
            String categoryCode,
            String categoryName,
            String name,
            String summary,
            String coverUrl,
            String priceMode,
            Long startingAmountMinor,
            String currency,
            String unitCode,
            String unitLabel,
            String priceNote,
            String cancellationNote,
            String availability,
            String primaryAction,
            long completedCount,
            UUID providerId,
            String providerName,
            String providerLogoUrl,
            String providerLogoAssetCode,
            String certificationLabel,
            boolean providerQualificationReviewed,
            String teamDescription,
            Double ratingAverage,
            long reviewCount,
            Integer positiveRate
    ) {
    }

    record DetailItemRow(
            String code,
            String title,
            String description,
            String iconKey,
            String imageUrl,
            String imageAssetCode
    ) {
    }

    record ServiceCityRow(
            String cityCode,
            String cityName,
            String provinceCode
    ) {
    }

    record ReviewTagRow(String code, String label, long tagCount) {
    }

    record ReviewRow(
            UUID id,
            String maskedUserName,
            int rating,
            LocalDate reviewDate,
            String content
    ) {
    }

    record ReviewImageRow(
            UUID reviewId,
            String imageUrl,
            String imageAssetCode
    ) {
    }
}
