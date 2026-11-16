package com.lowaltitude.inspection.infrastructure.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface InspectionServiceCatalogMapper {

    @Select("""
            SELECT count(*)
            FROM inspection_service
            WHERE status = 'ACTIVE'
              AND service_no IS NOT NULL
              AND category_code IS NOT NULL
              AND category_name IS NOT NULL
              AND summary IS NOT NULL
              AND price_mode IS NOT NULL
              AND currency IS NOT NULL
              AND availability IS NOT NULL
              AND primary_action IS NOT NULL
              AND (
                  price_mode = 'PROJECT_QUOTE'
                  OR starting_amount_minor IS NOT NULL
              )
              AND (
                  #{categoryCode} = 'ALL'
                  OR (#{categoryCode} = 'POWER' AND category_code IN (
                      'POWER',
                      'SUBSTATION',
                      'DISTRIBUTION',
                      'CABLE',
                      'PHOTOVOLTAIC'
                  ))
                  OR (#{categoryCode} != 'POWER'
                      AND category_code = #{categoryCode})
              )
              AND EXISTS (
                  SELECT 1
                  FROM inspection_service_city coverage
                  WHERE coverage.service_id = inspection_service.id
                    AND coverage.city_code = #{cityCode}
                    AND coverage.status = 'ACTIVE'
              )
              AND EXISTS (
                  SELECT 1
                  FROM inspection_service_booking_item item
                  WHERE item.service_id = inspection_service.id
                    AND item.status = 'ACTIVE'
              )
            """)
    long countActive(
            @Param("cityCode") String cityCode,
            @Param("categoryCode") String categoryCode
    );

    @Select("""
            <script>
            SELECT id service_id,
                   service_no,
                   category_code,
                   category_name,
                   title name,
                   summary,
                   cover_url,
                   price_mode,
                   starting_amount_minor,
                   currency,
                   unit_code,
                   unit_label,
                   price_note,
                   availability,
                   primary_action,
                   completed_count
            FROM inspection_service
            WHERE status = 'ACTIVE'
              AND service_no IS NOT NULL
              AND category_code IS NOT NULL
              AND category_name IS NOT NULL
              AND summary IS NOT NULL
              AND price_mode IS NOT NULL
              AND currency IS NOT NULL
              AND availability IS NOT NULL
              AND primary_action IS NOT NULL
              AND (
                  price_mode = 'PROJECT_QUOTE'
                  OR starting_amount_minor IS NOT NULL
              )
              AND (
                  #{categoryCode} = 'ALL'
                  OR (#{categoryCode} = 'POWER' AND category_code IN (
                      'POWER',
                      'SUBSTATION',
                      'DISTRIBUTION',
                      'CABLE',
                      'PHOTOVOLTAIC'
                  ))
                  OR (#{categoryCode} != 'POWER'
                      AND category_code = #{categoryCode})
              )
              AND EXISTS (
                  SELECT 1
                  FROM inspection_service_city coverage
                  WHERE coverage.service_id = inspection_service.id
                    AND coverage.city_code = #{cityCode}
                    AND coverage.status = 'ACTIVE'
              )
              AND EXISTS (
                  SELECT 1
                  FROM inspection_service_booking_item item
                  WHERE item.service_id = inspection_service.id
                    AND item.status = 'ACTIVE'
              )
            ORDER BY
              CASE WHEN #{sort} = 'COMPREHENSIVE' THEN
                CASE category_code
                  WHEN 'POWER' THEN 10
                  WHEN 'SUBSTATION' THEN 20
                  WHEN 'DISTRIBUTION' THEN 30
                  WHEN 'CABLE' THEN 40
                  WHEN 'PHOTOVOLTAIC' THEN 50
                  ELSE 90
                END
              END ASC,
              CASE WHEN #{sort} IN ('COMPREHENSIVE', 'SALES_DESC')
                THEN completed_count END DESC,
              CASE WHEN #{sort} IN ('PRICE_ASC', 'PRICE_DESC')
                AND price_mode = 'PROJECT_QUOTE' THEN 1 ELSE 0 END ASC,
              CASE WHEN #{sort} = 'PRICE_ASC'
                THEN starting_amount_minor END ASC NULLS LAST,
              CASE WHEN #{sort} = 'PRICE_DESC'
                THEN starting_amount_minor END DESC NULLS LAST,
              id ASC
            LIMIT #{limit}
            OFFSET #{offset}
            </script>
            """)
    List<ServiceCardRow> findActivePage(
            @Param("cityCode") String cityCode,
            @Param("categoryCode") String categoryCode,
            @Param("sort") String sort,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

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
            long completedCount
    ) {
    }

    record FeatureRow(String code, String name) {
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
