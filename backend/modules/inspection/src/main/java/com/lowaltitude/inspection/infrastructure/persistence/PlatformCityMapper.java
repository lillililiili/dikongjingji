package com.lowaltitude.inspection.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PlatformCityMapper {

    @Select("""
            SELECT city_code, city_name, city_pinyin, city_initials,
                   province_code, province_name, province_pinyin,
                   province_initials,
                   CASE WHEN platform_demo_metrics() THEN search_count ELSE recorded_search_count END AS search_count, service_status
            FROM platform_city
            WHERE status = 'ACTIVE'
            ORDER BY province_initials, province_pinyin, province_code,
                     city_initials, city_pinyin, city_code
            """)
    List<CityRow> findActiveCities();

    @Update("""
            UPDATE platform_city
            SET search_count = search_count + 1,
                recorded_search_count = recorded_search_count + 1,
                version = version + 1,
                updated_at = now()
            WHERE city_code = #{cityCode}
              AND status = 'ACTIVE'
            """)
    int incrementSearchCount(@Param("cityCode") String cityCode);

    // ---- 运营端“城市开通”（S3-1，V012）：只看可选的城市（status = 'ACTIVE'），全部用绑定参数 ----

    String OPS_FILTER = """
            WHERE c.status = 'ACTIVE'
              AND (CAST(#{serviceStatus} AS varchar) IS NULL OR c.service_status = #{serviceStatus})
              AND (CAST(#{provinceCode} AS varchar) IS NULL OR c.province_code = #{provinceCode})
              AND (CAST(#{keyword} AS varchar) IS NULL
                   OR c.city_name LIKE '%' || #{keyword} || '%'
                   OR c.city_pinyin LIKE '%' || lower(#{keyword}) || '%'
                   OR c.city_initials LIKE '%' || lower(#{keyword}) || '%'
                   OR c.city_code = #{keyword})
            """;

    String OPS_COLUMNS = """
            SELECT c.city_code, c.city_name, c.province_code, c.province_name,
                   c.service_status, c.service_status_changed_at, c.version
            FROM platform_city c
            """;

    @Select("SELECT count(*) FROM platform_city c " + OPS_FILTER)
    long countOpsCities(
            @Param("keyword") String keyword,
            @Param("serviceStatus") String serviceStatus,
            @Param("provinceCode") String provinceCode
    );

    /** 已开通在前，再筹备中、未开通；同一状态按省、城市拼音排。 */
    @Select(OPS_COLUMNS + OPS_FILTER + """
            ORDER BY CASE c.service_status WHEN 'OPEN' THEN 0 WHEN 'PREPARING' THEN 1 ELSE 2 END,
                     c.province_pinyin, c.province_code, c.city_pinyin, c.city_code
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<OpsCityRow> findOpsCities(
            @Param("keyword") String keyword,
            @Param("serviceStatus") String serviceStatus,
            @Param("provinceCode") String provinceCode,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select(OPS_COLUMNS + "WHERE c.city_code = #{cityCode} AND c.status = 'ACTIVE'")
    OpsCityRow findOpsCity(@Param("cityCode") String cityCode);

    /** 按版本号改开通状态；版本不对（别人刚改过）时一行也不改。 */
    @Update("""
            UPDATE platform_city
            SET service_status = #{serviceStatus},
                service_status_changed_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE city_code = #{cityCode}
              AND status = 'ACTIVE'
              AND version = #{version}
            """)
    int updateServiceStatus(
            @Param("cityCode") String cityCode,
            @Param("serviceStatus") String serviceStatus,
            @Param("version") int version,
            @Param("at") OffsetDateTime at
    );

    @Insert("""
            INSERT INTO platform_city_service_status_change (
                city_code, from_status, to_status, note, operator_id, operator_name, changed_at
            )
            VALUES (#{cityCode}, #{fromStatus}, #{toStatus}, #{note}, #{operatorId}, #{operatorName}, #{at})
            """)
    int insertServiceStatusChange(
            @Param("cityCode") String cityCode,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("note") String note,
            @Param("operatorId") java.util.UUID operatorId,
            @Param("operatorName") String operatorName,
            @Param("at") OffsetDateTime at
    );

    @Select("""
            SELECT id, city_code, from_status, to_status, note, operator_name, changed_at
            FROM platform_city_service_status_change
            WHERE city_code = #{cityCode}
            ORDER BY changed_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<ServiceStatusChangeRow> findServiceStatusChanges(@Param("cityCode") String cityCode,
                                                          @Param("limit") int limit);

    record CityRow(
            String cityCode,
            String cityName,
            String cityPinyin,
            String cityInitials,
            String provinceCode,
            String provinceName,
            String provincePinyin,
            String provinceInitials,
            long searchCount,
            String serviceStatus) {
    }

    record OpsCityRow(
            String cityCode,
            String cityName,
            String provinceCode,
            String provinceName,
            String serviceStatus,
            OffsetDateTime serviceStatusChangedAt,
            int version) {
    }

    record ServiceStatusChangeRow(
            java.util.UUID id,
            String cityCode,
            String fromStatus,
            String toStatus,
            String note,
            String operatorName,
            OffsetDateTime changedAt) {
    }
}
