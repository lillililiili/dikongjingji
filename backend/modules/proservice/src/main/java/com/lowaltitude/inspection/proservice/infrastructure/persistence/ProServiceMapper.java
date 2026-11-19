package com.lowaltitude.inspection.proservice.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 专业服务目录的读取（F2）。全部用绑定参数；排序只在三种固定写法里选，不拼客户端的字段。
 * 能被看到的服务：已上架，并且在这个城市上架（城市行在用）。
 */
public interface ProServiceMapper {

    String SERVICE_COLUMNS = """
            SELECT s.id,
                   s.provider_id,
                   s.type,
                   s.category_code,
                   s.title,
                   s.summary,
                   s.price_mode,
                   s.price_amount_minor,
                   s.price_unit,
                   s.price_note,
                   s.cover_url,
                   s.sample
            FROM pro_service s
            """;

    /** 在 #{cityCode} 能被看到的服务；目录、详情、服务商主页都经过它。 */
    String VISIBLE_IN_CITY = """
            WHERE s.status = 'PUBLISHED'
              AND EXISTS (
                  SELECT 1
                  FROM pro_service_city c
                  WHERE c.service_id = s.id
                    AND c.city_code = #{cityCode}
                    AND c.status = 'ACTIVE'
              )
            """;

    /** 目录的条件：类型、关键词（标题、简介、卡片标签）、筛选项（有任意一项即可）、报价方式。在 &lt;script&gt; 里。 */
    String CATALOG_FILTERS = """
              AND s.type = #{type}
            <if test="keyword != null">
              AND (
                  s.title ILIKE #{keyword} ESCAPE '\\'
                  OR s.summary ILIKE #{keyword} ESCAPE '\\'
                  OR EXISTS (
                      SELECT 1
                      FROM pro_service_item tag
                      WHERE tag.service_id = s.id
                        AND tag.kind = 'TAG'
                        AND tag.label ILIKE #{keyword} ESCAPE '\\'
                  )
              )
            </if>
            <if test="facets != null and !facets.isEmpty()">
              AND EXISTS (
                  SELECT 1
                  FROM pro_service_item facet
                  WHERE facet.service_id = s.id
                    AND facet.kind = 'FACET'
                    AND facet.code IN
                    <foreach collection="facets" item="facetCode" open="(" separator="," close=")">#{facetCode}</foreach>
              )
            </if>
            <if test="priceMode != null">
              AND s.price_mode = #{priceMode}
            </if>
            """;

    @Select("<script>SELECT count(*) FROM pro_service s " + VISIBLE_IN_CITY + CATALOG_FILTERS + "</script>")
    long countCatalog(
            @Param("cityCode") String cityCode,
            @Param("type") String type,
            @Param("keyword") String keyword,
            @Param("facets") List<String> facets,
            @Param("priceMode") String priceMode
    );

    /** 按价格排序时按项目报价的排在最后（false 在前）；同价、同为按项目报价时按综合顺序。 */
    @Select("<script>" + SERVICE_COLUMNS + VISIBLE_IN_CITY + CATALOG_FILTERS + """
            <choose>
              <when test="sort == 'PRICE_ASC'">
                ORDER BY (s.price_mode = 'PROJECT') ASC, s.price_amount_minor ASC, s.sort_order ASC, s.id ASC
              </when>
              <when test="sort == 'PRICE_DESC'">
                ORDER BY (s.price_mode = 'PROJECT') ASC, s.price_amount_minor DESC, s.sort_order ASC, s.id ASC
              </when>
              <otherwise>
                ORDER BY s.sort_order ASC, s.id ASC
              </otherwise>
            </choose>
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ServiceRow> findCatalog(
            @Param("cityCode") String cityCode,
            @Param("type") String type,
            @Param("keyword") String keyword,
            @Param("facets") List<String> facets,
            @Param("priceMode") String priceMode,
            @Param("sort") String sort,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /** 这个城市有没有这一类服务（不管关键词和筛选）：没有时目录显示“这个城市还没有开通”。 */
    @Select("SELECT count(*) FROM pro_service s " + VISIBLE_IN_CITY + " AND s.type = #{type}")
    long countOfferedInCity(@Param("cityCode") String cityCode, @Param("type") String type);

    @Select(SERVICE_COLUMNS + VISIBLE_IN_CITY + " AND s.id = #{serviceId}")
    ServiceRow findVisible(@Param("serviceId") UUID serviceId, @Param("cityCode") String cityCode);

    /** 服务商主页：这家在这个城市能被看到的服务，先按类型再按综合顺序。 */
    @Select(SERVICE_COLUMNS + VISIBLE_IN_CITY + """
              AND s.provider_id = #{providerId}
            ORDER BY CASE s.type
                         WHEN 'AERIAL' THEN 1
                         WHEN 'SURVEY' THEN 2
                         WHEN 'MAINTENANCE' THEN 3
                         ELSE 4
                     END,
                     s.sort_order ASC,
                     s.id ASC
            """)
    List<ServiceRow> findProviderServices(@Param("providerId") UUID providerId, @Param("cityCode") String cityCode);

    @Select("""
            <script>
            SELECT service_id, kind, code, label, detail
            FROM pro_service_item
            WHERE service_id IN
            <foreach collection="serviceIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              AND kind IN
            <foreach collection="kinds" item="kind" open="(" separator="," close=")">#{kind}</foreach>
            ORDER BY service_id, kind, sort_order, id
            </script>
            """)
    List<ItemRow> findItems(@Param("serviceIds") List<UUID> serviceIds, @Param("kinds") List<String> kinds);

    /** 一项服务在用的上架城市。 */
    @Select("""
            SELECT city_code
            FROM pro_service_city
            WHERE service_id = #{serviceId}
              AND status = 'ACTIVE'
            ORDER BY city_code
            """)
    List<String> findCities(@Param("serviceId") UUID serviceId);

    /** 这家服务商已上架的服务覆盖的城市。 */
    @Select("""
            SELECT DISTINCT c.city_code
            FROM pro_service s
            JOIN pro_service_city c ON c.service_id = s.id
            WHERE s.provider_id = #{providerId}
              AND s.status = 'PUBLISHED'
              AND c.status = 'ACTIVE'
            ORDER BY c.city_code
            """)
    List<String> findProviderCities(@Param("providerId") UUID providerId);

    /** 在这个城市有已上架服务的服务商（给需求的平台匹配和邀请用）。 */
    @Select("""
            SELECT DISTINCT s.provider_id
            FROM pro_service s
            JOIN pro_service_city c ON c.service_id = s.id
            WHERE s.status = 'PUBLISHED'
              AND c.status = 'ACTIVE'
              AND c.city_code = #{cityCode}
            ORDER BY s.provider_id
            """)
    List<UUID> findProviderIdsIn(@Param("cityCode") String cityCode);

    record ServiceRow(
            UUID id,
            UUID providerId,
            String type,
            String categoryCode,
            String title,
            String summary,
            String priceMode,
            Long priceAmountMinor,
            String priceUnit,
            String priceNote,
            String coverUrl,
            boolean sample
    ) {
    }

    record ItemRow(UUID serviceId, String kind, String code, String label, String detail) {
    }
}
