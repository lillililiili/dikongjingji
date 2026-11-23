package com.lowaltitude.inspection.proservice.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 运营端“专业服务目录”（S3-1，契约 openapi-ops-proservice.yaml 1.0.0）的读写。全部绑定参数；服务按版本号改；
 * 展示内容（pro_service_item）没有被别处引用，整组替换；上架城市不删行，只停用；改动记录只追加。
 * 公开读取仍走 ProServiceMapper（只读）。
 */
public interface ProServiceOpsMapper {

    String ROW_COLUMNS = """
            SELECT s.id service_id, s.type, s.category_code, s.title, s.provider_id, s.price_mode,
                   s.price_amount_minor, s.status, s.sample, s.sort_order,
                   (SELECT count(*) FROM pro_service_city c WHERE c.service_id = s.id AND c.status = 'ACTIVE') city_count,
                   s.updated_at, s.version
            FROM pro_service s
            """;

    /** 运营列表的条件。在 &lt;script&gt; 里。 */
    String FILTERS = """
            <where>
              <if test="type != null">AND s.type = #{type}</if>
              <if test="status != null">AND s.status = #{status}</if>
              <if test="cityCode != null">
                AND EXISTS (SELECT 1 FROM pro_service_city c
                            WHERE c.service_id = s.id AND c.city_code = #{cityCode} AND c.status = 'ACTIVE')
              </if>
              <if test="keyword != null">AND s.title ILIKE '%' || #{keyword} || '%'</if>
            </where>
            """;

    @Select("<script>SELECT count(*) FROM pro_service s " + FILTERS + "</script>")
    long countServices(@Param("keyword") String keyword, @Param("type") String type,
                       @Param("cityCode") String cityCode, @Param("status") String status);

    @Select("<script>" + ROW_COLUMNS + FILTERS + """
            ORDER BY s.updated_at DESC, s.id
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ServiceRow> findServices(@Param("keyword") String keyword, @Param("type") String type,
                                  @Param("cityCode") String cityCode, @Param("status") String status,
                                  @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            SELECT s.id service_id, s.status, s.version, s.updated_at, s.sample, s.provider_id, s.type, s.category_code,
                   s.title, s.summary, s.price_mode, s.price_amount_minor, s.price_unit, s.price_note, s.cover_url,
                   s.sort_order
            FROM pro_service s
            WHERE s.id = #{serviceId}
            """)
    DetailRow findService(@Param("serviceId") UUID serviceId);

    @Insert("""
            INSERT INTO pro_service (
                id, provider_id, type, category_code, title, summary, price_mode, price_amount_minor, price_unit,
                price_note, cover_url, sample, sort_order, status, created_at, updated_at
            )
            VALUES (
                #{id}, #{s.providerId}, #{s.type}, #{s.categoryCode}, #{s.title}, #{s.summary}, #{s.priceMode},
                #{s.priceAmountMinor}, #{s.priceUnit}, #{s.priceNote}, #{s.coverUrl}, false, #{s.sortOrder},
                'UNPUBLISHED', #{at}, #{at}
            )
            """)
    int insertService(@Param("id") UUID id, @Param("s") ServiceFields fields, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE pro_service
            SET provider_id = #{s.providerId}, type = #{s.type}, category_code = #{s.categoryCode}, title = #{s.title},
                summary = #{s.summary}, price_mode = #{s.priceMode}, price_amount_minor = #{s.priceAmountMinor},
                price_unit = #{s.priceUnit}, price_note = #{s.priceNote}, cover_url = #{s.coverUrl},
                sort_order = #{s.sortOrder}, version = version + 1, updated_at = #{at}
            WHERE id = #{id} AND version = #{version}
            """)
    int updateService(@Param("id") UUID id, @Param("s") ServiceFields fields, @Param("version") int version,
                      @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE pro_service
            SET status = #{status}, version = version + 1, updated_at = #{at}
            WHERE id = #{id} AND version = #{version}
            """)
    int updateStatus(@Param("id") UUID id, @Param("status") String status, @Param("version") int version,
                     @Param("at") OffsetDateTime at);

    // ---- 上架城市 ----

    @Select("""
            SELECT city_code FROM pro_service_city
            WHERE service_id = #{serviceId} AND status = 'ACTIVE'
            ORDER BY created_at, city_code
            """)
    List<String> findCities(@Param("serviceId") UUID serviceId);

    @Insert("""
            INSERT INTO pro_service_city (service_id, city_code, status, updated_at)
            VALUES (#{serviceId}, #{cityCode}, 'ACTIVE', #{at})
            ON CONFLICT (service_id, city_code) DO UPDATE
            SET status = 'ACTIVE', version = pro_service_city.version + 1, updated_at = #{at}
            WHERE pro_service_city.status <> 'ACTIVE'
            """)
    int activateCity(@Param("serviceId") UUID serviceId, @Param("cityCode") String cityCode,
                     @Param("at") OffsetDateTime at);

    @Update("""
            <script>
            UPDATE pro_service_city
            SET status = 'DISABLED', version = version + 1, updated_at = #{at}
            WHERE service_id = #{serviceId}
              AND status = 'ACTIVE'
              <if test="keep != null and !keep.isEmpty()">
              AND city_code NOT IN <foreach collection="keep" item="code" open="(" separator="," close=")">#{code}</foreach>
              </if>
            </script>
            """)
    int disableOtherCities(@Param("serviceId") UUID serviceId, @Param("keep") List<String> keep,
                           @Param("at") OffsetDateTime at);

    // ---- 展示内容 ----

    @Select("""
            SELECT kind, code, label, detail
            FROM pro_service_item
            WHERE service_id = #{serviceId}
            ORDER BY kind, sort_order, id
            """)
    List<ItemRow> findItems(@Param("serviceId") UUID serviceId);

    @Delete("DELETE FROM pro_service_item WHERE service_id = #{serviceId}")
    int deleteItems(@Param("serviceId") UUID serviceId);

    @Insert("""
            INSERT INTO pro_service_item (service_id, kind, code, label, detail, sort_order, created_at, updated_at)
            VALUES (#{serviceId}, #{item.kind}, #{item.code}, #{item.label}, #{item.detail}, #{order}, #{at}, #{at})
            """)
    int insertItem(@Param("serviceId") UUID serviceId, @Param("item") ItemRow item, @Param("order") int order,
                   @Param("at") OffsetDateTime at);

    // ---- 改动记录 ----

    @Insert("""
            INSERT INTO pro_service_change (service_id, action, summary, operator_id, operator_name, changed_at)
            VALUES (#{serviceId}, #{action}, #{summary}, #{operatorId}, #{operatorName}, #{at})
            """)
    int insertChange(@Param("serviceId") UUID serviceId, @Param("action") String action,
                     @Param("summary") String summary, @Param("operatorId") UUID operatorId,
                     @Param("operatorName") String operatorName, @Param("at") OffsetDateTime at);

    @Select("""
            SELECT id change_id, service_id, action, summary, operator_name, changed_at
            FROM pro_service_change
            WHERE service_id = #{serviceId}
            ORDER BY changed_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<ChangeRow> findChanges(@Param("serviceId") UUID serviceId, @Param("limit") int limit);

    record ServiceRow(UUID serviceId, String type, String categoryCode, String title, UUID providerId,
                      String priceMode, Long priceAmountMinor, String status, boolean sample, int sortOrder,
                      long cityCount, OffsetDateTime updatedAt, int version) {
    }

    record DetailRow(UUID serviceId, String status, int version, OffsetDateTime updatedAt, boolean sample,
                     UUID providerId, String type, String categoryCode, String title, String summary,
                     String priceMode, Long priceAmountMinor, String priceUnit, String priceNote, String coverUrl,
                     int sortOrder) {
    }

    record ServiceFields(UUID providerId, String type, String categoryCode, String title, String summary,
                         String priceMode, Long priceAmountMinor, String priceUnit, String priceNote,
                         String coverUrl, int sortOrder) {
    }

    record ItemRow(String kind, String code, String label, String detail) {
    }

    record ChangeRow(UUID changeId, UUID serviceId, String action, String summary, String operatorName,
                     OffsetDateTime changedAt) {
    }
}
