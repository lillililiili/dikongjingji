package com.lowaltitude.inspection.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 运营端“巡检服务目录”（S3-1，契约 openapi-ops-inspection-catalog.yaml 1.0.0）的读写。全部绑定参数；
 * 服务和类目按版本号改；城市覆盖、服务项目、详情项不删行，只停用（预约、方案里按编码引用过它们）；
 * 巡检方式（UAV / ROBOT）没有被引用，整组替换。改动记录只追加。
 */
public interface InspectionCatalogOpsMapper {

    // ---- 类目 ----

    @Select("""
            SELECT category.code, category.name, category.subtitle, category.parent_code, category.sort_order,
                   category.status, category.version, category.updated_at,
                   (SELECT count(*) FROM inspection_service service
                    WHERE service.category_code = category.code AND service.status = 'ACTIVE') active_service_count
            FROM inspection_category category
            ORDER BY COALESCE((SELECT parent.sort_order FROM inspection_category parent
                               WHERE parent.code = category.parent_code), category.sort_order),
                     category.parent_code NULLS FIRST, category.sort_order, category.code
            """)
    List<CategoryRow> findCategories();

    @Select("""
            SELECT category.code, category.name, category.subtitle, category.parent_code, category.sort_order,
                   category.status, category.version, category.updated_at,
                   (SELECT count(*) FROM inspection_service service
                    WHERE service.category_code = category.code AND service.status = 'ACTIVE') active_service_count
            FROM inspection_category category
            WHERE category.code = #{code}
            """)
    CategoryRow findCategory(@Param("code") String code);

    /** 这个类目和它的子类目里已上架的服务数（停用类目前要为 0）。 */
    @Select("""
            SELECT count(*)
            FROM inspection_service service
            JOIN inspection_category category ON category.code = service.category_code
            WHERE service.status = 'ACTIVE'
              AND (category.code = #{code} OR category.parent_code = #{code})
            """)
    long countActiveServicesUnder(@Param("code") String code);

    @Insert("""
            INSERT INTO inspection_category (code, name, subtitle, parent_code, sort_order, status, updated_at)
            VALUES (#{code}, #{name}, #{subtitle}, #{parentCode}, #{sortOrder}, 'ACTIVE', #{at})
            ON CONFLICT (code) DO NOTHING
            """)
    int insertCategory(
            @Param("code") String code,
            @Param("name") String name,
            @Param("subtitle") String subtitle,
            @Param("parentCode") String parentCode,
            @Param("sortOrder") int sortOrder,
            @Param("at") OffsetDateTime at
    );

    @Update("""
            UPDATE inspection_category
            SET name = #{name}, subtitle = #{subtitle}, sort_order = #{sortOrder},
                version = version + 1, updated_at = #{at}
            WHERE code = #{code} AND version = #{version}
            """)
    int updateCategory(
            @Param("code") String code,
            @Param("name") String name,
            @Param("subtitle") String subtitle,
            @Param("sortOrder") int sortOrder,
            @Param("version") int version,
            @Param("at") OffsetDateTime at
    );

    @Update("""
            UPDATE inspection_category
            SET status = #{status}, version = version + 1, updated_at = #{at}
            WHERE code = #{code} AND version = #{version}
            """)
    int updateCategoryStatus(
            @Param("code") String code,
            @Param("status") String status,
            @Param("version") int version,
            @Param("at") OffsetDateTime at
    );

    /** 类目改名后，服务上的类目名称快照（卡片、详情显示用）跟着改。 */
    @Update("""
            UPDATE inspection_service
            SET category_name = #{name}
            WHERE category_code = #{code}
            """)
    int renameCategoryOnServices(@Param("code") String code, @Param("name") String name);

    // ---- 服务 ----

    String SERVICE_ROW = """
            SELECT service.id service_id, service.service_no, service.title, service.category_code,
                   service.category_name, service.provider_id, organization.organization_name provider_name,
                   service.price_mode, service.starting_amount_minor, service.status, service.cover_url,
                   (SELECT count(*) FROM inspection_service_city city
                    WHERE city.service_id = service.id AND city.status = 'ACTIVE') city_count,
                   service.updated_at, service.version
            FROM inspection_service service
            LEFT JOIN identity_organization organization ON organization.id = service.provider_id
            """;

    /** 运营列表的条件：只列 ACTIVE（上架）和 DISABLED（未上架 / 已下架），不列已删除的。在 &lt;script&gt; 里。 */
    String SERVICE_FILTERS = """
            <where>
              service.status IN ('ACTIVE', 'DISABLED')
              <if test="status != null">AND service.status = #{status}</if>
              <if test="categoryCode != null">AND service.category_code = #{categoryCode}</if>
              <if test="cityCode != null">
                AND EXISTS (SELECT 1 FROM inspection_service_city city
                            WHERE city.service_id = service.id AND city.city_code = #{cityCode}
                              AND city.status = 'ACTIVE')
              </if>
              <if test="keyword != null">
                AND (service.title ILIKE '%' || #{keyword} || '%' OR service.service_no ILIKE '%' || #{keyword} || '%')
              </if>
            </where>
            """;

    @Select("<script>SELECT count(*) FROM inspection_service service " + SERVICE_FILTERS + "</script>")
    long countServices(
            @Param("keyword") String keyword,
            @Param("categoryCode") String categoryCode,
            @Param("cityCode") String cityCode,
            @Param("status") String status
    );

    @Select("<script>" + SERVICE_ROW + SERVICE_FILTERS + """
            ORDER BY service.updated_at DESC, service.id
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ServiceRow> findServices(
            @Param("keyword") String keyword,
            @Param("categoryCode") String categoryCode,
            @Param("cityCode") String cityCode,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select("""
            SELECT service.id service_id, service.service_no, service.status, service.version, service.updated_at,
                   service.provider_id, organization.organization_name provider_name, service.title, service.summary,
                   service.category_code, service.category_name, service.cover_url, service.city_code,
                   service.price_mode, service.starting_amount_minor, service.unit_code, service.unit_label,
                   service.price_note, service.cancellation_note, service.availability, service.primary_action,
                   service.includes_report, service.completed_count
            FROM inspection_service service
            LEFT JOIN identity_organization organization ON organization.id = service.provider_id
            WHERE service.id = #{serviceId}
              AND service.status IN ('ACTIVE', 'DISABLED')
            """)
    ServiceDetailRow findService(@Param("serviceId") UUID serviceId);

    @Insert("""
            INSERT INTO inspection_service (
                id, provider_id, title, cover_url, city_code, status, service_no, category_code, category_name,
                summary, price_mode, starting_amount_minor, currency, unit_code, unit_label, price_note,
                cancellation_note, availability, primary_action, includes_report, created_at, updated_at
            )
            VALUES (
                #{id}, #{s.providerId}, #{s.title}, #{s.coverUrl}, #{s.cityCode}, 'DISABLED', #{serviceNo},
                #{s.categoryCode}, #{s.categoryName}, #{s.summary}, #{s.priceMode}, #{s.startingAmountMinor},
                #{s.currency}, #{s.unitCode}, #{s.unitLabel},
                #{s.priceNote}, #{s.cancellationNote}, #{s.availability}, #{s.primaryAction}, #{s.includesReport},
                #{at}, #{at}
            )
            """)
    int insertService(@Param("id") UUID id, @Param("serviceNo") String serviceNo, @Param("s") ServiceFields fields,
                      @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE inspection_service
            SET provider_id = #{s.providerId}, title = #{s.title}, cover_url = #{s.coverUrl}, city_code = #{s.cityCode},
                category_code = #{s.categoryCode}, category_name = #{s.categoryName}, summary = #{s.summary},
                price_mode = #{s.priceMode}, starting_amount_minor = #{s.startingAmountMinor},
                currency = #{s.currency},
                unit_code = #{s.unitCode}, unit_label = #{s.unitLabel}, price_note = #{s.priceNote},
                cancellation_note = #{s.cancellationNote}, availability = #{s.availability},
                primary_action = #{s.primaryAction}, includes_report = #{s.includesReport},
                version = version + 1, updated_at = #{at}
            WHERE id = #{id} AND version = #{version} AND status IN ('ACTIVE', 'DISABLED')
            """)
    int updateService(@Param("id") UUID id, @Param("s") ServiceFields fields, @Param("version") int version,
                      @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE inspection_service
            SET status = #{status}, version = version + 1, updated_at = #{at}
            WHERE id = #{id} AND version = #{version} AND status IN ('ACTIVE', 'DISABLED')
            """)
    int updateServiceStatus(@Param("id") UUID id, @Param("status") String status, @Param("version") int version,
                            @Param("at") OffsetDateTime at);

    // ---- 城市覆盖 ----

    @Select("""
            SELECT coverage.city_code, city.city_name
            FROM inspection_service_city coverage
            LEFT JOIN platform_city city ON city.city_code = coverage.city_code
            WHERE coverage.service_id = #{serviceId} AND coverage.status = 'ACTIVE'
            ORDER BY city.province_code, city.city_pinyin, coverage.city_code
            """)
    List<CityRow> findServiceCities(@Param("serviceId") UUID serviceId);

    /** 平台城市目录里可选的这些城市（不在里面的是不认识的城市）。 */
    @Select("""
            <script>
            SELECT city_code, city_name
            FROM platform_city
            WHERE status = 'ACTIVE'
              AND city_code IN <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach>
            </script>
            """)
    List<CityRow> findDirectoryCities(@Param("codes") List<String> codes);

    @Insert("""
            INSERT INTO inspection_service_city (service_id, city_code, status, updated_at)
            VALUES (#{serviceId}, #{cityCode}, 'ACTIVE', #{at})
            ON CONFLICT (service_id, city_code) DO UPDATE
            SET status = 'ACTIVE', version = inspection_service_city.version + 1, updated_at = #{at}
            WHERE inspection_service_city.status <> 'ACTIVE'
            """)
    int activateCity(@Param("serviceId") UUID serviceId, @Param("cityCode") String cityCode,
                     @Param("at") OffsetDateTime at);

    @Update("""
            <script>
            UPDATE inspection_service_city
            SET status = 'DISABLED', version = version + 1, updated_at = #{at}
            WHERE service_id = #{serviceId}
              AND status = 'ACTIVE'
              AND city_code NOT IN <foreach collection="keep" item="code" open="(" separator="," close=")">#{code}</foreach>
            </script>
            """)
    int disableOtherCities(@Param("serviceId") UUID serviceId, @Param("keep") List<String> keep,
                           @Param("at") OffsetDateTime at);

    // ---- 服务项目（预约页“巡检项目”的选项、卡片标签） ----

    @Select("""
            SELECT item_code code, item_label title, description, icon_key
            FROM inspection_service_booking_item
            WHERE service_id = #{serviceId} AND status = 'ACTIVE'
            ORDER BY display_order, id
            """)
    List<ItemRow> findBookingItems(@Param("serviceId") UUID serviceId);

    @Insert("""
            INSERT INTO inspection_service_booking_item (
                service_id, item_code, item_label, description, icon_key, display_order, status, updated_at
            )
            VALUES (#{serviceId}, #{item.code}, #{item.title}, #{item.description}, #{item.iconKey}, #{order}, 'ACTIVE',
                    #{at})
            ON CONFLICT (service_id, item_code) DO UPDATE
            SET item_label = EXCLUDED.item_label, description = EXCLUDED.description, icon_key = EXCLUDED.icon_key,
                display_order = EXCLUDED.display_order, status = 'ACTIVE',
                version = inspection_service_booking_item.version + 1, updated_at = #{at}
            """)
    int upsertBookingItem(@Param("serviceId") UUID serviceId, @Param("item") ItemRow item, @Param("order") int order,
                          @Param("at") OffsetDateTime at);

    @Update("""
            <script>
            UPDATE inspection_service_booking_item
            SET status = 'DISABLED', version = version + 1, updated_at = #{at}
            WHERE service_id = #{serviceId}
              AND status = 'ACTIVE'
              <if test="keep != null and !keep.isEmpty()">
              AND item_code NOT IN <foreach collection="keep" item="code" open="(" separator="," close=")">#{code}</foreach>
              </if>
            </script>
            """)
    int disableOtherBookingItems(@Param("serviceId") UUID serviceId, @Param("keep") List<String> keep,
                                 @Param("at") OffsetDateTime at);

    // ---- 详情项（价格因素、能力、服务流程、交付物、保障） ----

    @Select("""
            SELECT section_type, item_code code, title, description, icon_key
            FROM inspection_service_detail_item
            WHERE service_id = #{serviceId} AND status = 'ACTIVE'
            ORDER BY section_type, display_order, id
            """)
    List<DetailItemRow> findDetailItems(@Param("serviceId") UUID serviceId);

    @Insert("""
            INSERT INTO inspection_service_detail_item (
                service_id, section_type, item_code, title, description, icon_key, display_order, status, updated_at
            )
            VALUES (#{serviceId}, #{section}, #{item.code}, #{item.title}, #{item.description}, #{item.iconKey},
                    #{order}, 'ACTIVE', #{at})
            ON CONFLICT (service_id, section_type, item_code) DO UPDATE
            SET title = EXCLUDED.title, description = EXCLUDED.description, icon_key = EXCLUDED.icon_key,
                display_order = EXCLUDED.display_order, status = 'ACTIVE',
                version = inspection_service_detail_item.version + 1, updated_at = #{at}
            """)
    int upsertDetailItem(@Param("serviceId") UUID serviceId, @Param("section") String section,
                         @Param("item") ItemRow item, @Param("order") int order, @Param("at") OffsetDateTime at);

    @Update("""
            <script>
            UPDATE inspection_service_detail_item
            SET status = 'DISABLED', version = version + 1, updated_at = #{at}
            WHERE service_id = #{serviceId}
              AND section_type = #{section}
              AND status = 'ACTIVE'
              <if test="keep != null and !keep.isEmpty()">
              AND item_code NOT IN <foreach collection="keep" item="code" open="(" separator="," close=")">#{code}</foreach>
              </if>
            </script>
            """)
    int disableOtherDetailItems(@Param("serviceId") UUID serviceId, @Param("section") String section,
                                @Param("keep") List<String> keep, @Param("at") OffsetDateTime at);

    // ---- 巡检方式 ----

    @Select("""
            SELECT method_code FROM inspection_service_method WHERE service_id = #{serviceId} ORDER BY method_code
            """)
    List<String> findMethods(@Param("serviceId") UUID serviceId);

    @Delete("DELETE FROM inspection_service_method WHERE service_id = #{serviceId}")
    int deleteMethods(@Param("serviceId") UUID serviceId);

    @Insert("""
            INSERT INTO inspection_service_method (service_id, method_code, created_at)
            VALUES (#{serviceId}, #{methodCode}, #{at})
            """)
    int insertMethod(@Param("serviceId") UUID serviceId, @Param("methodCode") String methodCode,
                     @Param("at") OffsetDateTime at);

    // ---- 改动记录 ----

    @Insert("""
            INSERT INTO inspection_catalog_change (
                subject_type, subject_id, action, summary, operator_id, operator_name, changed_at
            )
            VALUES (#{subjectType}, #{subjectId}, #{action}, #{summary}, #{operatorId}, #{operatorName}, #{at})
            """)
    int insertChange(
            @Param("subjectType") String subjectType,
            @Param("subjectId") UUID subjectId,
            @Param("action") String action,
            @Param("summary") String summary,
            @Param("operatorId") UUID operatorId,
            @Param("operatorName") String operatorName,
            @Param("at") OffsetDateTime at
    );

    @Select("""
            SELECT id change_id, subject_type, subject_id, action, summary, operator_name, changed_at
            FROM inspection_catalog_change
            WHERE subject_type = #{subjectType} AND subject_id = #{subjectId}
            ORDER BY changed_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<ChangeRow> findChanges(@Param("subjectType") String subjectType, @Param("subjectId") UUID subjectId,
                                @Param("limit") int limit);

    record CategoryRow(String code, String name, String subtitle, String parentCode, int sortOrder, String status,
                       int version, OffsetDateTime updatedAt, long activeServiceCount) {
    }

    record ServiceRow(UUID serviceId, String serviceNo, String title, String categoryCode, String categoryName,
                      UUID providerId, String providerName, String priceMode, Long startingAmountMinor, String status,
                      String coverUrl, long cityCount, OffsetDateTime updatedAt, int version) {
    }

    record ServiceDetailRow(UUID serviceId, String serviceNo, String status, int version, OffsetDateTime updatedAt,
                            UUID providerId, String providerName, String title, String summary, String categoryCode,
                            String categoryName, String coverUrl, String cityCode, String priceMode,
                            Long startingAmountMinor, String unitCode, String unitLabel, String priceNote,
                            String cancellationNote, String availability, String primaryAction,
                            boolean includesReport, long completedCount) {
    }

    /** 服务表上运营能改的列（新建和修改共用）。cityCode 是主城市：覆盖城市里的第一个；有计价方式时 currency 为 CNY。 */
    record ServiceFields(UUID providerId, String title, String summary, String categoryCode, String categoryName,
                         String coverUrl, String cityCode, String priceMode, Long startingAmountMinor,
                         String currency, String unitCode, String unitLabel, String priceNote, String cancellationNote,
                         String availability, String primaryAction, boolean includesReport) {
    }

    record CityRow(String cityCode, String cityName) {
    }

    record ItemRow(String code, String title, String description, String iconKey) {
    }

    record DetailItemRow(String sectionType, String code, String title, String description, String iconKey) {
    }

    record ChangeRow(UUID changeId, String subjectType, UUID subjectId, String action, String summary,
                     String operatorName, OffsetDateTime changedAt) {
    }
}
