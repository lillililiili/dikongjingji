package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 运营端首页“经营概览”（契约 contracts/openapi-ops-dashboard.yaml）里账号和机构的汇总数。只做 COUNT，不读明细。
 * 客户指有在用的客户角色、没注销的账号。时间区间都是 [from, to)。
 */
public interface IdentityOpsStatsMapper {

    String CUSTOMER = """
            FROM identity_user u
            WHERE u.status <> 'CLOSED'
              AND EXISTS (SELECT 1 FROM identity_user_role ur JOIN identity_role r ON r.id = ur.role_id
                          WHERE ur.user_id = u.id AND r.role_code = 'CUSTOMER'
                            AND (ur.valid_until IS NULL OR ur.valid_until > now()))
            """;

    record DayRow(LocalDate day, long count) {
    }

    @Select("SELECT COUNT(*) " + CUSTOMER)
    long customers();

    @Select("SELECT COUNT(*) " + CUSTOMER + " AND u.created_at >= #{from} AND u.created_at < #{to}")
    long newCustomers(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    @Select("SELECT COUNT(*) " + CUSTOMER + """
              AND EXISTS (SELECT 1 FROM identity_login_session s
                          WHERE s.user_id = u.id AND s.last_active_at >= #{from} AND s.last_active_at < #{to})
            """)
    long activeCustomers(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    @Select("SELECT (u.created_at AT TIME ZONE 'Asia/Shanghai')::date AS day, COUNT(*) AS count " + CUSTOMER
            + " AND u.created_at >= #{from} AND u.created_at < #{to} GROUP BY 1 ORDER BY 1")
    List<DayRow> dailyNewCustomers(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    @Select("SELECT COUNT(*) FROM identity_enterprise WHERE status = 'ACTIVE'")
    long enterprises();

    @Select("""
            SELECT COUNT(*) FROM identity_organization
            WHERE organization_type = 'PROVIDER' AND status = 'ACTIVE'
            """)
    long providers();

    @Select("""
            SELECT COUNT(*) FROM identity_organization
            WHERE organization_type = 'PROVIDER' AND status = 'ACTIVE' AND qualification_reviewed_at IS NOT NULL
            """)
    long qualificationReviewedProviders();

    @Select("""
            SELECT COUNT(*) FROM identity_organization
            WHERE organization_type = 'PROVIDER' AND status = 'ACTIVE'
              AND created_at >= #{from} AND created_at < #{to}
            """)
    long newProviders(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    @Select("SELECT COUNT(*) FROM identity_organization_qualification WHERE status = 'SUBMITTED'")
    long pendingQualifications();
}
