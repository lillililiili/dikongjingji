package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface IdentityPersistenceMapper
        extends BaseMapper<IdentityUserEntity>, IdentityPersistenceReader, IdentityProfileReader,
        IdentityOrganizationReader {

    // Only what the account may show about itself: no login name, mobile or password hash.
    // An inactive organization is left out rather than hiding the whole profile.
    @Override
    @Select("""
            SELECT u.id AS subject_id,
                   u.display_name,
                   o.organization_name,
                   o.organization_type
              FROM identity_user u
              LEFT JOIN identity_organization o
                ON o.id = u.default_organization_id
               AND o.status = 'ACTIVE'
             WHERE u.id = #{subjectId}
               AND u.status = 'ACTIVE'
            """)
    Optional<PersistedIdentityProfile> findActiveProfileById(@Param("subjectId") UUID subjectId);

    // An organization by id, only while it is active; name, type, when its qualification was reviewed and
    // whether its goods and services are suspended from listing (V013).
    @Override
    @Select("""
            SELECT o.id AS organization_id,
                   o.organization_name,
                   o.organization_type,
                   o.qualification_reviewed_at,
                   o.listing_suspended
              FROM identity_organization o
             WHERE o.id = #{organizationId}
               AND o.status = 'ACTIVE'
            """)
    Optional<PersistedIdentityOrganization> findActiveOrganizationById(@Param("organizationId") UUID organizationId);

    // R6-4: of these organizations, the active ones whose qualification the platform has reviewed.
    @Override
    @Select("""
            <script>
            SELECT o.id
              FROM identity_organization o
             WHERE o.id IN
            <foreach collection="organizationIds" item="id" open="(" separator="," close=")">#{id}</foreach>
               AND o.status = 'ACTIVE'
               AND o.qualification_reviewed_at IS NOT NULL
            </script>
            """)
    List<UUID> findQualificationReviewedOrganizationIds(@Param("organizationIds") List<UUID> organizationIds);

    @Override
    @Select("""
            SELECT u.id AS subject_id,
                   u.login_name,
                   u.password_hash,
                   u.status AS user_status,
                   u.token_version,
                   u.default_organization_id,
                   o.status AS organization_status,
                   m.member_status
              FROM identity_user u
              LEFT JOIN identity_organization o
                ON o.id = u.default_organization_id
              LEFT JOIN identity_organization_member m
                ON m.user_id = u.id
               AND m.organization_id = u.default_organization_id
             WHERE u.login_name = #{loginName} AND u.registered_via = 'PASSWORD'
            """)
    Optional<PersistedIdentityUser> findUserByLoginName(@Param("loginName") String loginName);

    @Override
    @Select("""
            SELECT u.id AS subject_id,
                   u.login_name,
                   u.password_hash,
                   u.status AS user_status,
                   u.token_version,
                   u.default_organization_id,
                   o.status AS organization_status,
                   m.member_status
              FROM identity_user u
              LEFT JOIN identity_organization o
                ON o.id = u.default_organization_id
              LEFT JOIN identity_organization_member m
                ON m.user_id = u.id
               AND m.organization_id = u.default_organization_id
             WHERE u.id = #{subjectId}
            """)
    Optional<PersistedIdentityUser> findUserById(@Param("subjectId") UUID subjectId);

    @Override
    @Select("""
            SELECT r.role_code,
                   r.status AS role_status,
                   r.scope_type,
                   ur.organization_id,
                   ur.valid_from,
                   ur.valid_until,
                   p.permission_code
              FROM identity_user_role ur
              JOIN identity_user u
                ON u.id = ur.user_id
              JOIN identity_role r
                ON r.id = ur.role_id
              LEFT JOIN identity_role_permission rp
                ON rp.role_id = r.id
              LEFT JOIN identity_permission p
                ON p.id = rp.permission_id
             WHERE ur.user_id = #{subjectId}
               AND r.status = 'ACTIVE'
               AND ur.valid_from <= CURRENT_TIMESTAMP
               AND (ur.valid_until IS NULL OR ur.valid_until > CURRENT_TIMESTAMP)
               AND (
                    (r.scope_type = 'GLOBAL' AND ur.organization_id IS NULL)
                    OR
                    (r.scope_type = 'ORGANIZATION'
                        AND ur.organization_id = u.default_organization_id)
               )
            """)
    List<PersistedRolePermission> findRolePermissions(@Param("subjectId") UUID subjectId);
}
