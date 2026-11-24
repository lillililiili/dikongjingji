package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.OpsStatsExclusion;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 运营端首页“经营概览”（契约 contracts/openapi-ops-dashboard.yaml）里账号和机构的汇总数。只做 COUNT，不读明细。
 * 客户指有在用的客户角色、没注销的账号。时间区间都是 [from, to)。
 * excluded 是不计入的账号和机构（{@link OpsStatsExclusion}）；待处理的数不排除。
 */
public interface IdentityOpsStatsMapper {

    String CUSTOMER = """
            FROM identity_user u
            WHERE u.status <> 'CLOSED'
              AND u.id <> ALL (CAST(#{excluded.userArray} AS uuid[]))
              AND EXISTS (SELECT 1 FROM identity_user_role ur JOIN identity_role r ON r.id = ur.role_id
                          WHERE ur.user_id = u.id AND r.role_code = 'CUSTOMER'
                            AND (ur.valid_until IS NULL OR ur.valid_until > now()))
            """;

    record DayRow(LocalDate day, long count) {
    }

    @Select("SELECT COUNT(*) " + CUSTOMER)
    long customers(@Param("excluded") OpsStatsExclusion excluded);

    @Select("SELECT COUNT(*) " + CUSTOMER + " AND u.created_at >= #{from} AND u.created_at < #{to}")
    long newCustomers(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                      @Param("excluded") OpsStatsExclusion excluded);

    @Select("SELECT COUNT(*) " + CUSTOMER + """
              AND EXISTS (SELECT 1 FROM identity_login_session s
                          WHERE s.user_id = u.id AND s.last_active_at >= #{from} AND s.last_active_at < #{to})
            """)
    long activeCustomers(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                         @Param("excluded") OpsStatsExclusion excluded);

    @Select("SELECT (u.created_at AT TIME ZONE 'Asia/Shanghai')::date AS day, COUNT(*) AS count " + CUSTOMER
            + " AND u.created_at >= #{from} AND u.created_at < #{to} GROUP BY 1 ORDER BY 1")
    List<DayRow> dailyNewCustomers(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                                   @Param("excluded") OpsStatsExclusion excluded);

    /** 企业组织：企业本身在名单里，或者是名单里的账号建的，都不计入。 */
    @Select("""
            SELECT COUNT(*) FROM identity_enterprise
            WHERE status = 'ACTIVE'
              AND organization_id <> ALL (CAST(#{excluded.organizationArray} AS uuid[]))
              AND created_by <> ALL (CAST(#{excluded.userArray} AS uuid[]))
            """)
    long enterprises(@Param("excluded") OpsStatsExclusion excluded);

    @Select("""
            SELECT COUNT(*) FROM identity_organization
            WHERE organization_type = 'PROVIDER' AND status = 'ACTIVE'
              AND id <> ALL (CAST(#{excluded.organizationArray} AS uuid[]))
            """)
    long providers(@Param("excluded") OpsStatsExclusion excluded);

    @Select("""
            SELECT COUNT(*) FROM identity_organization
            WHERE organization_type = 'PROVIDER' AND status = 'ACTIVE' AND qualification_reviewed_at IS NOT NULL
              AND id <> ALL (CAST(#{excluded.organizationArray} AS uuid[]))
            """)
    long qualificationReviewedProviders(@Param("excluded") OpsStatsExclusion excluded);

    @Select("""
            SELECT COUNT(*) FROM identity_organization
            WHERE organization_type = 'PROVIDER' AND status = 'ACTIVE'
              AND created_at >= #{from} AND created_at < #{to}
              AND id <> ALL (CAST(#{excluded.organizationArray} AS uuid[]))
            """)
    long newProviders(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to,
                      @Param("excluded") OpsStatsExclusion excluded);

    @Select("SELECT COUNT(*) FROM identity_organization_qualification WHERE status = 'SUBMITTED'")
    long pendingQualifications();

    /** 名单里的账号：登录名完整匹配或以某个前缀开头。两个参数是逗号分隔的列表，空串表示没有。 */
    @Select("""
            SELECT id FROM identity_user
            WHERE login_name = ANY (string_to_array(#{names}, ','))
               OR EXISTS (SELECT 1 FROM unnest(string_to_array(#{prefixes}, ',')) AS p(prefix)
                          WHERE starts_with(login_name, p.prefix))
            """)
    List<UUID> findUserIds(@Param("names") String names, @Param("prefixes") String prefixes);

    /** 名单里的机构：机构编码完整匹配或以某个前缀开头。 */
    @Select("""
            SELECT id FROM identity_organization
            WHERE organization_code = ANY (string_to_array(#{codes}, ','))
               OR EXISTS (SELECT 1 FROM unnest(string_to_array(#{prefixes}, ',')) AS p(prefix)
                          WHERE starts_with(organization_code, p.prefix))
            """)
    List<UUID> findOrganizationIds(@Param("codes") String codes, @Param("prefixes") String prefixes);
}
