package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface IdentityPersistenceMapper
        extends BaseMapper<IdentityUserEntity>, IdentityPersistenceReader {

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
             WHERE u.login_name = #{loginName}
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
