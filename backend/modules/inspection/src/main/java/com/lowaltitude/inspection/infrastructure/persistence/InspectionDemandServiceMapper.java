package com.lowaltitude.inspection.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 可用于选定报价的预约目录；提交时锁定选项及其资质和城市覆盖。 */
public interface InspectionDemandServiceMapper {
    String BOOKABLE_SERVICES = """
            SELECT service.id AS service_id, service.title
            FROM inspection_service service
            JOIN inspection_service_metrics metrics ON metrics.service_id = service.id
            JOIN inspection_service_city city ON city.service_id = service.id AND city.city_code = #{cityCode}
            JOIN inspection_provider_profile profile ON profile.provider_id = service.provider_id
            JOIN identity_organization org ON org.id = service.provider_id
            """ + InspectionServiceCatalogMapper.VISIBLE_IN_CITY + """
              AND service.provider_id = #{providerId}
              AND org.organization_type = 'PROVIDER' AND org.status = 'ACTIVE' AND NOT org.listing_suspended
              AND profile.status = 'ACTIVE' AND city.status = 'ACTIVE'
              AND service.availability IN ('RESERVABLE', 'AVAILABLE') AND service.primary_action = 'RESERVE'
            """;
    @Select(BOOKABLE_SERVICES + "ORDER BY service.title, service.id")
    List<BookableService> findBookable(@Param("providerId") UUID providerId, @Param("cityCode") String cityCode);

    @Select(BOOKABLE_SERVICES + """
              AND service.id = #{serviceId}
            FOR UPDATE OF service, city FOR SHARE OF org, profile
            """)
    BookableService lockBookable(@Param("providerId") UUID providerId, @Param("cityCode") String cityCode,
                                 @Param("serviceId") UUID serviceId);

    record BookableService(UUID serviceId, String title) { }
}
