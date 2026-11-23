package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 首个平台运营账号的一次性引导（S6）要的几条读写：登录名是否已有、在用的平台机构、没有时建一个、留一条身份审计。
 * 建账号、加成员、授角色沿用 {@link OrganizationOnboardingMapper}。全部用绑定参数，不改、不删已有数据。
 */
public interface PlatformOperatorBootstrapMapper {

    @Select("SELECT EXISTS (SELECT 1 FROM identity_user WHERE login_name = #{loginName})")
    boolean loginNameTaken(@Param("loginName") String loginName);

    /** 在用的平台机构：编码是 PLATFORM 的优先，其余按建立先后。 */
    @Select("""
            SELECT id
              FROM identity_organization
             WHERE organization_type = 'PLATFORM'
               AND status = 'ACTIVE'
             ORDER BY CASE WHEN organization_code = #{code} THEN 0 ELSE 1 END, created_at, id
             LIMIT 1
            """)
    UUID findActivePlatformOrganization(@Param("code") String code);

    /** 建平台机构；编码已被占用时什么都不写，返回 null。 */
    @Select("""
            INSERT INTO identity_organization (organization_code, organization_name, organization_type,
                                               created_at, updated_at)
            VALUES (#{code}, #{name}, 'PLATFORM', #{at}, #{at})
            ON CONFLICT (organization_code) DO NOTHING
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertPlatformOrganization(@Param("code") String code, @Param("name") String name,
                                    @Param("at") OffsetDateTime at);

    /** 身份审计：启动时建了平台运营账号（没有操作人，请求号固定）。 */
    @Insert("""
            INSERT INTO identity_auth_audit (actor_user_id, action_code, target_type, target_id, organization_id,
                                             request_id, result_code, occurred_at)
            VALUES (NULL, 'PLATFORM_OPERATOR_BOOTSTRAP', 'IDENTITY_USER', #{accountId}, #{organizationId},
                    'startup-bootstrap', 'CREATED', #{at})
            """)
    int recordBootstrap(@Param("accountId") UUID accountId, @Param("organizationId") UUID organizationId,
                        @Param("at") OffsetDateTime at);
}
