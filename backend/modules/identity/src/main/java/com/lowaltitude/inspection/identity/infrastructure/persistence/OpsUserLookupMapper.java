package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 运营端“用户查询”（契约 contracts/openapi-ops-lookup.yaml）的只读查询，以及打开详情时的查看记录（写进已有的
 * identity_auth_audit）。手机号只用于精确查找和在服务里打码。
 */
public interface OpsUserLookupMapper {

    /** phone、userId、loginName 精确；displayName 是 ILIKE 用的 %…%。四个都空时列全部。 */
    String FILTER = """
            FROM identity_user u
            WHERE 1 = 1
            <if test="phone != null">AND (u.mobile = #{phone} OR u.login_name = #{phone})</if>
            <if test="userId != null">AND u.id = #{userId}</if>
            <if test="loginName != null or displayName != null">
              AND (<if test="loginName != null">u.login_name = #{loginName}</if>
                   <if test="loginName != null and displayName != null">OR</if>
                   <if test="displayName != null">u.display_name ILIKE #{displayName} ESCAPE '\\'</if>)
            </if>
            """;

    String COLUMNS = """
            SELECT u.id, u.display_name, u.login_name, u.mobile, u.status,
                   EXISTS (SELECT 1 FROM identity_closure_request c
                           WHERE c.user_id = u.id AND c.status = 'PENDING') closing,
                   u.registered_via,
                   COALESCE((SELECT string_agg(DISTINCT r.role_code, ',' ORDER BY r.role_code)
                             FROM identity_user_role ur JOIN identity_role r ON r.id = ur.role_id
                             WHERE ur.user_id = u.id AND r.status = 'ACTIVE'
                               AND statement_timestamp() >= ur.valid_from
                               AND (ur.valid_until IS NULL OR ur.valid_until > statement_timestamp())
                               AND ((r.scope_type = 'GLOBAL' AND ur.organization_id IS NULL)
                                 OR (r.scope_type = 'ORGANIZATION' AND ur.organization_id = u.default_organization_id
                                     AND EXISTS (SELECT 1 FROM identity_organization_member m
                                                 JOIN identity_organization o ON o.id = m.organization_id
                                                 WHERE m.organization_id = ur.organization_id AND m.user_id = u.id
                                                   AND m.member_status = 'ACTIVE' AND o.status = 'ACTIVE')))), '') roles,
                   u.created_at
            """;

    @Select("<script>SELECT count(*)\n" + FILTER + "</script>")
    long countUsers(@Param("phone") String phone, @Param("userId") UUID userId, @Param("loginName") String loginName,
                    @Param("displayName") String displayName);

    @Select("<script>" + COLUMNS + FILTER + """
            ORDER BY u.created_at DESC, u.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """ + "</script>")
    List<UserRow> findUsers(@Param("phone") String phone, @Param("userId") UUID userId,
                            @Param("loginName") String loginName, @Param("displayName") String displayName,
                            @Param("limit") int limit, @Param("offset") int offset);

    @Select(COLUMNS + "FROM identity_user u WHERE u.id = #{userId}")
    UserRow findUser(@Param("userId") UUID userId);

    @Select("""
            SELECT (SELECT due_at FROM identity_closure_request
                    WHERE user_id = #{userId} AND status = 'PENDING'
                    ORDER BY requested_at DESC LIMIT 1) closing_due_at,
                   u.closed_at,
                   (SELECT count(*) FROM identity_login_session s
                    WHERE s.user_id = u.id AND s.revoked_at IS NULL
                      AND s.last_active_at > #{since}) active_device_count,
                   (SELECT max(s.last_active_at) FROM identity_login_session s
                    WHERE s.user_id = u.id) last_active_at
            FROM identity_user u WHERE u.id = #{userId}
            """)
    UserExtraRow findUserExtra(@Param("userId") UUID userId, @Param("since") OffsetDateTime since);

    @Select("""
            SELECT o.id, o.organization_name, o.organization_type
            FROM identity_organization_member m
            JOIN identity_organization o ON o.id = m.organization_id
            WHERE m.user_id = #{userId} AND m.member_status = 'ACTIVE'
            ORDER BY o.organization_name, o.id
            """)
    List<OrganizationRow> findOrganizations(@Param("userId") UUID userId);

    @Select("""
            SELECT o.id, o.organization_name, m.member_role, m.status
            FROM identity_enterprise_member m
            JOIN identity_organization o ON o.id = m.organization_id
            WHERE m.user_id = #{userId}
            ORDER BY m.joined_at DESC, m.id
            """)
    List<EnterpriseRow> findEnterprises(@Param("userId") UUID userId);

    /** 运营端打开用户或订单详情时记一条：谁、看了什么。 */
    @Insert("""
            INSERT INTO identity_auth_audit (actor_user_id, action_code, target_type, target_id, request_id,
                                             result_code, occurred_at)
            VALUES (#{actorId}, #{actionCode}, #{targetType}, #{targetId}, #{requestId}, 'VIEWED', #{at})
            """)
    int insertView(@Param("actorId") UUID actorId, @Param("actionCode") String actionCode,
                   @Param("targetType") String targetType, @Param("targetId") UUID targetId,
                   @Param("requestId") String requestId, @Param("at") OffsetDateTime at);

    record UserRow(UUID id, String displayName, String loginName, String mobile, String status, boolean closing,
                   String registeredVia, String roles, OffsetDateTime createdAt) {
    }

    record UserExtraRow(OffsetDateTime closingDueAt, OffsetDateTime closedAt, long activeDeviceCount,
                        OffsetDateTime lastActiveAt) {
    }

    record OrganizationRow(UUID id, String organizationName, String organizationType) {
    }

    record EnterpriseRow(UUID id, String organizationName, String memberRole, String status) {
    }
}
