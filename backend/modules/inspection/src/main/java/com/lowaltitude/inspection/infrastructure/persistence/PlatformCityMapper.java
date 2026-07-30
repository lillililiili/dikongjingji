package com.lowaltitude.inspection.infrastructure.persistence;

import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PlatformCityMapper {

    @Select("""
            SELECT city_code, city_name, city_pinyin, city_initials,
                   province_code, province_name, province_pinyin,
                   province_initials, search_count
            FROM platform_city
            WHERE status = 'ACTIVE'
            ORDER BY province_initials, province_pinyin, province_code,
                     city_initials, city_pinyin, city_code
            """)
    List<CityRow> findActiveCities();

    @Update("""
            UPDATE platform_city
            SET search_count = search_count + 1,
                version = version + 1,
                updated_at = now()
            WHERE city_code = #{cityCode}
              AND status = 'ACTIVE'
            """)
    int incrementSearchCount(@Param("cityCode") String cityCode);

    record CityRow(
            String cityCode,
            String cityName,
            String cityPinyin,
            String cityInitials,
            String provinceCode,
            String provinceName,
            String provincePinyin,
            String provinceInitials,
            long searchCount) {
    }
}
