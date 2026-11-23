package com.lowaltitude.inspection.trade.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 运营端“收款账户”（S3-1，迁移 V009）：经营机构（身份模块的机构表，只读）和它在交易模块登记的收款账户。全部绑定参数。
 */
public interface TradeMerchantAccountMapper {

    String LIST_FILTER = """
            FROM identity_organization o
            LEFT JOIN trade_merchant_account a ON a.organization_id = o.id
            WHERE o.organization_type = 'PROVIDER'
            <if test="keyword != null">
              AND (o.organization_name ILIKE #{keyword} ESCAPE '\\' OR o.organization_code ILIKE #{keyword} ESCAPE '\\')
            </if>
            <if test="registered != null and registered">
              AND a.organization_id IS NOT NULL
            </if>
            <if test="registered != null and !registered">
              AND a.organization_id IS NULL
            </if>
            """;

    String COLUMNS = """
            SELECT o.id organization_id,
                   o.organization_code,
                   o.organization_name,
                   o.status organization_status,
                   a.organization_id IS NOT NULL registered,
                   a.sub_mchid,
                   COALESCE(a.active, false) active,
                   a.updated_at,
                   a.version
            """;

    @Select("<script>SELECT count(*)\n" + LIST_FILTER + "</script>")
    long countAccounts(@Param("keyword") String keyword, @Param("registered") Boolean registered);

    @Select("<script>" + COLUMNS + LIST_FILTER + """
            ORDER BY CASE WHEN a.organization_id IS NULL THEN 0 ELSE 1 END, o.organization_name, o.id
            LIMIT #{limit} OFFSET #{offset}
            """ + "</script>")
    List<AccountRow> findAccounts(@Param("keyword") String keyword, @Param("registered") Boolean registered,
                                  @Param("limit") int limit, @Param("offset") int offset);

    @Select(COLUMNS + """
            FROM identity_organization o
            LEFT JOIN trade_merchant_account a ON a.organization_id = o.id
            WHERE o.id = #{organizationId}
            """)
    AccountRow findAccount(@Param("organizationId") UUID organizationId);

    @Select("SELECT organization_type FROM identity_organization WHERE id = #{organizationId}")
    String findOrganizationType(@Param("organizationId") UUID organizationId);

    /** 同一家机构的登记排队（第一次登记时还没有账户行可锁，所以锁机构行）。 */
    @Select("SELECT id FROM identity_organization WHERE id = #{organizationId} FOR UPDATE")
    UUID lockOrganization(@Param("organizationId") UUID organizationId);

    @Select("""
            INSERT INTO trade_merchant_account (organization_id, channel, sub_mchid, active, created_at, updated_at)
            VALUES (#{organizationId}, 'WECHAT', #{subMchid}, #{active}, #{at}, #{at})
            ON CONFLICT (organization_id) DO NOTHING
            RETURNING version
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Integer insertAccount(@Param("organizationId") UUID organizationId, @Param("subMchid") String subMchid,
                          @Param("active") boolean active, @Param("at") OffsetDateTime at);

    /** 带版本号改；版本不对时返回 null。expectedVersion 为 null 时不比版本（批量导入）。 */
    @Select("""
            UPDATE trade_merchant_account
            SET sub_mchid = #{subMchid},
                active = #{active},
                version = version + 1,
                updated_at = #{at}
            WHERE organization_id = #{organizationId}
              AND (CAST(#{expectedVersion} AS integer) IS NULL OR version = CAST(#{expectedVersion} AS integer))
            RETURNING version
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Integer updateAccount(@Param("organizationId") UUID organizationId, @Param("subMchid") String subMchid,
                          @Param("active") boolean active, @Param("expectedVersion") Integer expectedVersion,
                          @Param("at") OffsetDateTime at);

    @Insert("""
            INSERT INTO trade_merchant_account_operation (organization_id, action, detail, operator_id, operator_name,
                                                          occurred_at)
            VALUES (#{organizationId}, #{action}, #{detail}, #{operatorId}, #{operatorName}, #{at})
            """)
    int insertOperation(@Param("organizationId") UUID organizationId, @Param("action") String action,
                        @Param("detail") String detail, @Param("operatorId") UUID operatorId,
                        @Param("operatorName") String operatorName, @Param("at") OffsetDateTime at);

    @Select("""
            SELECT id, action, detail, operator_name, occurred_at
            FROM trade_merchant_account_operation
            WHERE organization_id = #{organizationId}
            ORDER BY occurred_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<OperationRow> findOperations(@Param("organizationId") UUID organizationId, @Param("limit") int limit);

    record AccountRow(UUID organizationId, String organizationCode, String organizationName,
                      String organizationStatus, boolean registered, String subMchid, boolean active,
                      OffsetDateTime updatedAt, Integer version) {
    }

    record OperationRow(UUID id, String action, String detail, String operatorName, OffsetDateTime occurredAt) {
    }
}
