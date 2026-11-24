package com.lowaltitude.inspection.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 服务商名录（F1 需求报价用）：有服务商资料（在用）的服务商机构，以及它们在用的服务覆盖的城市。
 * 只读巡检模块自己的表和身份模块的机构（名称、资质审核通过时间，R6-4）；不返回联系方式。
 */
public interface InspectionProviderDirectoryMapper {

    String PROVIDER_COLUMNS = """
            SELECT o.id provider_id,
                   o.organization_name name,
                   p.certification_label,
                   p.team_description description,
                   o.qualification_reviewed_at IS NOT NULL qualification_reviewed
            FROM inspection_provider_profile p
            JOIN identity_organization o ON o.id = p.provider_id
            WHERE p.status = 'ACTIVE'
              AND o.status = 'ACTIVE'
              AND o.organization_type = 'PROVIDER'
            """;

    /** 服务这个城市的服务商：至少有一项在用的服务覆盖它；被挂起上架（身份迁移 V013）的不算。 */
    @Select(PROVIDER_COLUMNS + """
              AND NOT o.listing_suspended
              AND EXISTS (
                  SELECT 1
                  FROM inspection_service s
                  JOIN inspection_service_city c ON c.service_id = s.id
                  WHERE s.provider_id = p.provider_id
                    AND s.status = 'ACTIVE'
                    AND c.status = 'ACTIVE'
                    AND c.city_code = #{cityCode}
              )
            ORDER BY o.organization_name ASC, o.id ASC
            """)
    List<ProviderRow> findProvidersIn(@Param("cityCode") String cityCode);

    @Select("<script>" + PROVIDER_COLUMNS + """
              AND p.provider_id IN
            <foreach collection="providerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY o.organization_name ASC, o.id ASC
            </script>
            """)
    List<ProviderRow> findProviders(@Param("providerIds") List<UUID> providerIds);

    /** 这家服务商在用的服务覆盖的城市；没有在用的服务商资料时为空。 */
    @Select("""
            SELECT DISTINCT c.city_code
            FROM inspection_service s
            JOIN inspection_service_city c ON c.service_id = s.id
            JOIN inspection_provider_profile p ON p.provider_id = s.provider_id
            JOIN identity_organization o ON o.id = s.provider_id
            WHERE s.provider_id = #{providerId}
              AND s.status = 'ACTIVE'
              AND c.status = 'ACTIVE'
              AND p.status = 'ACTIVE'
              AND o.status = 'ACTIVE'
            ORDER BY c.city_code
            """)
    List<String> findCitiesServed(@Param("providerId") UUID providerId);

    /**
     * 服务商主页（F2）：这家服务商在这个城市能被看到、能预约的巡检服务。可见条件和巡检目录同一份（VISIBLE_IN_CITY）。
     */
    @Select("""
            SELECT service.id service_id,
                   service.title,
                   service.summary,
                   service.category_name,
                   service.price_mode,
                   service.starting_amount_minor,
                   service.unit_label,
                   service.price_note
            """ + InspectionServiceCatalogMapper.FROM_SERVICE + InspectionServiceCatalogMapper.VISIBLE_IN_CITY + """
              AND service.provider_id = #{providerId}
            ORDER BY service.title ASC, service.id ASC
            """)
    List<ServiceRow> findServicesOf(@Param("providerId") UUID providerId, @Param("cityCode") String cityCode);

    record ProviderRow(UUID providerId, String name, String certificationLabel, String description,
                       boolean qualificationReviewed) {
    }

    record ServiceRow(
            UUID serviceId,
            String title,
            String summary,
            String categoryName,
            String priceMode,
            Long startingAmountMinor,
            String unitLabel,
            String priceNote
    ) {
    }
}
