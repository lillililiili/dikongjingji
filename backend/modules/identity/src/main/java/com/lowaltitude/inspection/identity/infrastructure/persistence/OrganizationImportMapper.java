package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 批量导入经营主体和操作员账号（S2-3，contracts/openapi-ops-import.yaml 的 ORGANIZATIONS 类）。按 organization_code
 * 找机构、按登录名找账号；账号、成员、角色、操作记录的写法沿用 {@link OrganizationOnboardingMapper}。全部绑定参数。
 */
public interface OrganizationImportMapper {

    @Select("""
            SELECT id, organization_type, status
            FROM identity_organization
            WHERE organization_code = #{code}
            """)
    OrganizationRef findByCode(@Param("code") String code);

    @Select("""
            <script>
            SELECT id, organization_code, organization_type, status
            FROM identity_organization
            WHERE organization_code IN
            <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach>
            </script>
            """)
    List<OrganizationCodeRef> findByCodes(@Param("codes") List<String> codes);

    @Select("""
            INSERT INTO identity_organization (organization_code, organization_name, organization_type, status,
                                               qualification_reviewed_at, listing_suspended, created_at, updated_at)
            VALUES (#{code}, #{name}, #{type}, #{status}, CAST(#{reviewedAt} AS timestamptz),
                    #{type} = 'PROVIDER' AND CAST(#{reviewedAt} AS timestamptz) IS NULL, #{at}, #{at})
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertOrganization(@Param("code") String code, @Param("name") String name, @Param("type") String type,
                            @Param("status") String status, @Param("reviewedAt") OffsetDateTime reviewedAt,
                            @Param("at") OffsetDateTime at);

    /** 第二次导入同一个编码：改名称、状态和审核时间（类型不改）；经营机构能不能上架按有没有审核时间（V013）。 */
    @Update("""
            UPDATE identity_organization
            SET organization_name = #{name},
                status = #{status},
                qualification_reviewed_at = CAST(#{reviewedAt} AS timestamptz),
                listing_suspended = organization_type = 'PROVIDER' AND CAST(#{reviewedAt} AS timestamptz) IS NULL,
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{organizationId}
            """)
    int updateOrganization(@Param("organizationId") UUID organizationId, @Param("name") String name,
                           @Param("status") String status, @Param("reviewedAt") OffsetDateTime reviewedAt,
                           @Param("at") OffsetDateTime at);

    /** 登录名对应的账号和它的默认机构（没有这个登录名时为 null）。 */
    @Select("""
            SELECT id account_id, default_organization_id
            FROM identity_user
            WHERE login_name = #{loginName}
            """)
    LoginRef findLogin(@Param("loginName") String loginName);

    @Select("""
            <script>
            SELECT login_name, default_organization_id
            FROM identity_user
            WHERE login_name IN
            <foreach collection="logins" item="login" open="(" separator="," close=")">#{login}</foreach>
            </script>
            """)
    List<LoginOwner> findLogins(@Param("logins") List<String> logins);

    @Update("""
            UPDATE identity_user
            SET display_name = #{displayName},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{accountId}
              AND display_name <> #{displayName}
            """)
    int updateDisplayName(@Param("accountId") UUID accountId, @Param("displayName") String displayName,
                          @Param("at") OffsetDateTime at);

    /** 这个账号在这家机构现在有效的角色编码。 */
    @Select("""
            SELECT r.role_code
            FROM identity_user_role ur
            JOIN identity_role r ON r.id = ur.role_id
            WHERE ur.user_id = #{accountId}
              AND ur.organization_id = #{organizationId}
              AND ur.valid_from <= #{at}
              AND (ur.valid_until IS NULL OR ur.valid_until > #{at})
            """)
    List<String> findRoles(@Param("accountId") UUID accountId, @Param("organizationId") UUID organizationId,
                           @Param("at") OffsetDateTime at);

    record OrganizationRef(UUID id, String organizationType, String status) {
    }

    record OrganizationCodeRef(UUID id, String organizationCode, String organizationType, String status) {
    }

    record LoginRef(UUID accountId, UUID defaultOrganizationId) {
    }

    record LoginOwner(String loginName, UUID defaultOrganizationId) {
    }
}
