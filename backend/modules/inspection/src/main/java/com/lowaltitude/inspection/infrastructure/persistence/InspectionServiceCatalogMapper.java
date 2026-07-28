package com.lowaltitude.inspection.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface InspectionServiceCatalogMapper {

    @Select("""
            SELECT count(*)
            FROM inspection_service
            WHERE city_code = #{cityCode}
              AND status = 'ACTIVE'
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
              AND EXISTS (
                  SELECT 1
                  FROM inspection_service_booking_item item
                  WHERE item.service_id = inspection_service.id
                    AND item.status = 'ACTIVE'
              )
            """)
    long countActive(@Param("cityCode") String cityCode);

    @Select("""
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
                   availability,
                   primary_action
            FROM inspection_service
            WHERE city_code = #{cityCode}
              AND status = 'ACTIVE'
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
              AND EXISTS (
                  SELECT 1
                  FROM inspection_service_booking_item item
                  WHERE item.service_id = inspection_service.id
                    AND item.status = 'ACTIVE'
              )
            ORDER BY id
            LIMIT #{limit}
            OFFSET #{offset}
            """)
    List<ServiceCardRow> findActivePage(
            @Param("cityCode") String cityCode,
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
            String availability,
            String primaryAction
    ) {
    }

    record FeatureRow(String code, String name) {
    }
}
