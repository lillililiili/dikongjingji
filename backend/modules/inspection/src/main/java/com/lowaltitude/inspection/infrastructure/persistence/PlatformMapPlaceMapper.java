package com.lowaltitude.inspection.infrastructure.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PlatformMapPlaceMapper {
    @Select("""
            SELECT p.city_code || ':' || p.place_key AS place_id, p.name, p.address,
                   ST_Y(p.location::geometry) AS latitude, ST_X(p.location::geometry) AS longitude, p.sample
            FROM platform_map_place p
            JOIN platform_city c ON c.city_code = p.city_code AND c.status = 'ACTIVE'
            WHERE p.city_code = #{cityCode} AND p.published
              AND (strpos(regexp_replace(lower(p.name), '[[:space:]]+', '', 'g'), lower(#{keyword})) > 0
                   OR strpos(regexp_replace(lower(p.address), '[[:space:]]+', '', 'g'), lower(#{keyword})) > 0
                   OR strpos(lower(c.city_name), lower(#{keyword})) > 0)
            ORDER BY ST_Distance(p.location,
                         ST_SetSRID(ST_MakePoint(CAST(#{longitude} AS float8), CAST(#{latitude} AS float8)), 4326)::geography),
                     p.sort_order, p.place_key
            LIMIT 30
            """)
    List<PlaceRow> find(@Param("cityCode") String cityCode, @Param("keyword") String keyword,
                        @Param("latitude") Double latitude, @Param("longitude") Double longitude);

    record PlaceRow(String placeId, String name, String address, double latitude, double longitude, boolean sample) {}
}
