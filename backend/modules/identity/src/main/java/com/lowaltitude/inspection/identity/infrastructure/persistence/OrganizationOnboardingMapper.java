package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 运营端“机构入驻与资质审核”（S3-1，迁移 V007）的读写。全部用绑定参数；只碰商家/服务商类（PROVIDER）和企业客户类
 * （CUSTOMER）机构，平台机构不在运营端管理范围内。“当前时间”由调用方传入。
 */
public interface OrganizationOnboardingMapper {

    /** 资质状态：有资质记录看记录；没有记录但有审核时间（V005 时代由种子或线下写入）算已通过；都没有是未提交。 */
    String QUALIFICATION_STATUS = """
            CASE WHEN q.status IS NOT NULL THEN q.status
                 WHEN o.qualification_reviewed_at IS NOT NULL THEN 'APPROVED'
                 ELSE 'NOT_SUBMITTED' END
            """;

    String ORGANIZATION_COLUMNS = """
            SELECT o.id AS organization_id,
                   o.organization_code,
                   o.organization_name,
                   o.organization_type,
                   o.city_code,
                   o.status,
                   o.qualification_reviewed_at,
                   o.created_at,
                   o.version,
            """ + QUALIFICATION_STATUS + """
                   AS qualification_status
              FROM identity_organization o
              LEFT JOIN identity_organization_qualification q ON q.organization_id = o.id
            """;

    String MANAGED = "o.organization_type IN ('PROVIDER', 'CUSTOMER')";

    String LIST_FILTER = """
             WHERE o.organization_type IN ('PROVIDER', 'CUSTOMER')
               AND (CAST(#{type} AS varchar) IS NULL OR o.organization_type = #{type})
               AND (CAST(#{keyword} AS varchar) IS NULL
                    OR o.organization_name LIKE '%' || #{keyword} || '%'
                    OR o.organization_code = #{keyword})
               AND (CAST(#{qualificationStatus} AS varchar) IS NULL OR (""" + QUALIFICATION_STATUS + """
                   ) = #{qualificationStatus})
            """;

    @Select("SELECT count(*) FROM identity_organization o "
            + "LEFT JOIN identity_organization_qualification q ON q.organization_id = o.id " + LIST_FILTER)
    long countOrganizations(@Param("keyword") String keyword, @Param("type") String type,
                            @Param("qualificationStatus") String qualificationStatus);

    /** 待审核的排在前面，再按新建时间倒序。 */
    @Select(ORGANIZATION_COLUMNS + LIST_FILTER + """
             ORDER BY CASE WHEN q.status = 'SUBMITTED' THEN 0 ELSE 1 END, o.created_at DESC, o.id DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<OrganizationRow> findOrganizations(@Param("keyword") String keyword, @Param("type") String type,
                                            @Param("qualificationStatus") String qualificationStatus,
                                            @Param("limit") int limit, @Param("offset") int offset);

    @Select(ORGANIZATION_COLUMNS + " WHERE o.id = #{organizationId} AND " + MANAGED)
    OrganizationRow findOrganization(@Param("organizationId") UUID organizationId);

    /** 同一机构的运营操作排队：资料、审核、附件、账号、角色的改动不会交错。 */
    @Select("SELECT o.id FROM identity_organization o WHERE o.id = #{organizationId} AND " + MANAGED
            + " FOR UPDATE")
    UUID lockOrganization(@Param("organizationId") UUID organizationId);

    @Select("SELECT id FROM identity_organization WHERE ops_create_key = #{createKey}")
    UUID findOrganizationByCreateKey(@Param("createKey") UUID createKey);

    /** Serialize the entire signup, including its one-use SMS verification, across application instances. */
    @Select("""
            SELECT 1 FROM (
                SELECT pg_advisory_xact_lock(hashtextextended('identity-signup:' || CAST(#{key} AS text), 0))
            ) locked
            """)
    Integer lockSignupKey(@Param("key") UUID key);

    @Select("SELECT id, signup_request_signature AS signature FROM identity_organization WHERE ops_create_key = #{key}")
    SignupReceipt findSignupReceipt(@Param("key") UUID key);

    @Update("UPDATE identity_organization SET signup_request_signature = #{signature} WHERE id = #{id}")
    int saveSignupSignature(@Param("id") UUID id, @Param("signature") String signature);

    /** V012 did not save a signature. Only compare business fields recoverable from the original signup. */
    @Select("""
            SELECT EXISTS (
                SELECT 1 FROM identity_organization o
                JOIN identity_organization_operation op ON op.organization_id = o.id AND op.action = 'SIGNUP'
                JOIN identity_user u ON u.id = op.operator_id
                JOIN identity_organization_qualification q ON q.organization_id = o.id
                WHERE o.id = #{id} AND o.organization_name = #{name} AND o.city_code = #{city}
                  AND q.subject_name = #{subject} AND q.license_number = #{license}
                  AND q.business_scope IS NOT DISTINCT FROM CAST(#{scope} AS varchar)
                  AND op.operator_name = #{contact} AND u.mobile = #{phone} AND u.login_name = #{login}
                  AND u.updated_at = u.created_at
                  AND NOT EXISTS (SELECT 1 FROM identity_organization_operation later
                      WHERE later.organization_id = o.id AND later.action = 'QUALIFICATION_SUBMIT'
                        AND later.occurred_at > op.occurred_at)
            )
            """)
    boolean matchesLegacySignup(@Param("id") UUID id, @Param("name") String name, @Param("city") String city,
                                @Param("subject") String subject, @Param("license") String license,
                                @Param("scope") String scope, @Param("contact") String contact,
                                @Param("phone") String phone, @Param("login") String login);

    record SignupReceipt(UUID id, String signature) { }

    /** 新建机构；经营机构在资质审核通过前不能上架（V013）。同一个幂等键已经建过时什么都不写，返回 null。 */
    @Select("""
            INSERT INTO identity_organization (
                organization_code, organization_name, organization_type, city_code, ops_create_key,
                listing_suspended, created_at, updated_at
            )
            VALUES (#{code}, #{name}, #{type}, #{cityCode}, #{createKey}, #{type} = 'PROVIDER', #{at}, #{at})
            ON CONFLICT (ops_create_key) WHERE ops_create_key IS NOT NULL DO NOTHING
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertOrganization(@Param("code") String code, @Param("name") String name, @Param("type") String type,
                            @Param("cityCode") String cityCode, @Param("createKey") UUID createKey,
                            @Param("at") OffsetDateTime at);

    /**
     * 审核通过写时间，驳回或重新提交清空（App 上的“资质已审核”只看这一列）。经营机构同时放开或挂起上架（V013）：
     * 通过后商品和服务在 App 可见，驳回、重新提交后不可见。
     */
    @Update("""
            UPDATE identity_organization
            SET qualification_reviewed_at = CAST(#{reviewedAt} AS timestamptz),
                listing_suspended = organization_type = 'PROVIDER' AND CAST(#{reviewedAt} AS timestamptz) IS NULL,
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{organizationId}
            """)
    int updateQualificationReviewedAt(@Param("organizationId") UUID organizationId,
                                      @Param("reviewedAt") OffsetDateTime reviewedAt,
                                      @Param("at") OffsetDateTime at);

    /**
     * 没有资质记录的机构改了附件：撤下“资质已审核”。以前审核过的经营机构同时挂起上架，等录了资料再审；
     * 从来没审核过的旧机构不受影响（已经挂起的保持挂起）。
     */
    @Update("""
            UPDATE identity_organization
            SET listing_suspended = listing_suspended
                    OR (organization_type = 'PROVIDER' AND qualification_reviewed_at IS NOT NULL),
                qualification_reviewed_at = NULL,
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{organizationId}
            """)
    int withdrawUnrecordedReview(@Param("organizationId") UUID organizationId, @Param("at") OffsetDateTime at);

    // ---- 资质资料 ----

    @Select("""
            SELECT organization_id, subject_name, license_number, business_scope, qualification_note, status,
                   submitted_at, reviewed_at, reject_reason, version
              FROM identity_organization_qualification
             WHERE organization_id = #{organizationId}
            """)
    QualificationRow findQualification(@Param("organizationId") UUID organizationId);

    @Insert("""
            INSERT INTO identity_organization_qualification (
                organization_id, subject_name, license_number, business_scope, qualification_note, status,
                submitted_at, submitted_by, created_at, updated_at
            )
            VALUES (#{organizationId}, #{subjectName}, #{licenseNumber}, #{businessScope}, #{qualificationNote},
                    'SUBMITTED', #{at}, #{operatorId}, #{at}, #{at})
            """)
    int insertQualification(@Param("organizationId") UUID organizationId, @Param("subjectName") String subjectName,
                            @Param("licenseNumber") String licenseNumber,
                            @Param("businessScope") String businessScope,
                            @Param("qualificationNote") String qualificationNote,
                            @Param("operatorId") UUID operatorId, @Param("at") OffsetDateTime at);

    /** 改资料：回到待审核，清掉上一次的审核结果；版本不对时一行也不改。 */
    @Update("""
            UPDATE identity_organization_qualification
            SET subject_name = #{subjectName},
                license_number = #{licenseNumber},
                business_scope = #{businessScope},
                qualification_note = #{qualificationNote},
                status = 'SUBMITTED',
                submitted_at = #{at},
                submitted_by = #{operatorId},
                reviewed_at = NULL,
                reviewed_by = NULL,
                reject_reason = NULL,
                version = version + 1,
                updated_at = #{at}
            WHERE organization_id = #{organizationId}
              AND version = #{version}
            """)
    int updateQualification(@Param("organizationId") UUID organizationId, @Param("subjectName") String subjectName,
                            @Param("licenseNumber") String licenseNumber,
                            @Param("businessScope") String businessScope,
                            @Param("qualificationNote") String qualificationNote,
                            @Param("operatorId") UUID operatorId, @Param("version") int version,
                            @Param("at") OffsetDateTime at);

    /** 附件有改动：已审核或已驳回的资料回到待审核（文字不变）。 */
    @Update("""
            UPDATE identity_organization_qualification
            SET status = 'SUBMITTED',
                submitted_at = #{at},
                submitted_by = #{operatorId},
                reviewed_at = NULL,
                reviewed_by = NULL,
                reject_reason = NULL,
                version = version + 1,
                updated_at = #{at}
            WHERE organization_id = #{organizationId}
              AND status <> 'SUBMITTED'
            """)
    int reopenQualification(@Param("organizationId") UUID organizationId, @Param("operatorId") UUID operatorId,
                            @Param("at") OffsetDateTime at);

    /** 审核：只能审待审核的那一版（版本号对得上）。 */
    @Update("""
            UPDATE identity_organization_qualification
            SET status = #{status},
                reviewed_at = #{at},
                reviewed_by = #{operatorId},
                reject_reason = #{rejectReason},
                version = version + 1,
                updated_at = #{at}
            WHERE organization_id = #{organizationId}
              AND status = 'SUBMITTED'
              AND version = #{version}
            """)
    int reviewQualification(@Param("organizationId") UUID organizationId, @Param("status") String status,
                            @Param("rejectReason") String rejectReason, @Param("operatorId") UUID operatorId,
                            @Param("version") int version, @Param("at") OffsetDateTime at);

    // ---- 附件 ----

    @Select("""
            SELECT id, organization_id, file_name, content_type, size_bytes, sha256, storage_key, uploaded_at
              FROM identity_organization_attachment
             WHERE organization_id = #{organizationId}
               AND removed_at IS NULL
             ORDER BY uploaded_at DESC, id DESC
            """)
    List<AttachmentRow> findAttachments(@Param("organizationId") UUID organizationId);

    @Select("""
            SELECT id, organization_id, file_name, content_type, size_bytes, sha256, storage_key, uploaded_at
              FROM identity_organization_attachment
             WHERE organization_id = #{organizationId}
               AND id = #{attachmentId}
               AND removed_at IS NULL
            """)
    AttachmentRow findAttachment(@Param("organizationId") UUID organizationId,
                                 @Param("attachmentId") UUID attachmentId);

    @Select("""
            INSERT INTO identity_organization_attachment (
                id, organization_id, file_name, content_type, size_bytes, sha256, storage_key, uploaded_by, uploaded_at
            )
            VALUES (#{id}, #{organizationId}, #{fileName}, #{contentType}, #{sizeBytes}, #{sha256}, #{storageKey},
                    #{operatorId}, #{at})
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertAttachment(@Param("id") UUID id, @Param("organizationId") UUID organizationId,
                          @Param("fileName") String fileName, @Param("contentType") String contentType,
                          @Param("sizeBytes") long sizeBytes, @Param("sha256") String sha256,
                          @Param("storageKey") String storageKey, @Param("operatorId") UUID operatorId,
                          @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE identity_organization_attachment
            SET removed_at = #{at},
                removed_by = #{operatorId}
            WHERE organization_id = #{organizationId}
              AND id = #{attachmentId}
              AND removed_at IS NULL
            """)
    int removeAttachment(@Param("organizationId") UUID organizationId, @Param("attachmentId") UUID attachmentId,
                         @Param("operatorId") UUID operatorId, @Param("at") OffsetDateTime at);

    // ---- 账号与角色 ----

    /** 默认机构是这家的账号（机构角色只在默认机构下生效）。不读口令、手机号。 */
    @Select("""
            SELECT u.id AS account_id, u.login_name, u.display_name, u.status, u.created_at
              FROM identity_user u
             WHERE u.default_organization_id = #{organizationId}
             ORDER BY u.created_at, u.id
            """)
    List<AccountRow> findAccounts(@Param("organizationId") UUID organizationId);

    @Select("""
            SELECT u.id AS account_id, u.login_name, u.display_name, u.status, u.created_at
              FROM identity_user u
             WHERE u.default_organization_id = #{organizationId}
               AND u.id = #{accountId}
             FOR UPDATE
            """)
    AccountRow lockAccount(@Param("organizationId") UUID organizationId, @Param("accountId") UUID accountId);

    /** 这些账号现在有效的角色：全局的，和限定在这家机构的。 */
    @Select("""
            <script>
            SELECT ur.user_id AS account_id, r.role_code
              FROM identity_user_role ur
              JOIN identity_role r ON r.id = ur.role_id
             WHERE ur.user_id IN
            <foreach collection="accountIds" item="id" open="(" separator="," close=")">#{id}</foreach>
               AND r.status = 'ACTIVE'
               AND ur.valid_from &lt;= #{at}
               AND (ur.valid_until IS NULL OR ur.valid_until &gt; #{at})
               AND (ur.organization_id IS NULL OR ur.organization_id = #{organizationId})
             ORDER BY r.role_code
            </script>
            """)
    List<AccountRoleRow> findAccountRoles(@Param("organizationId") UUID organizationId,
                                          @Param("accountIds") List<UUID> accountIds,
                                          @Param("at") OffsetDateTime at);

    /** 新账号；登录名已被占用时什么都不写，返回 null。 */
    @Select("""
            INSERT INTO identity_user (login_name, display_name, password_hash, default_organization_id,
                                       created_at, updated_at)
            VALUES (#{loginName}, #{displayName}, #{passwordHash}, #{organizationId}, #{at}, #{at})
            ON CONFLICT (login_name) DO NOTHING
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertAccount(@Param("loginName") String loginName, @Param("displayName") String displayName,
                       @Param("passwordHash") String passwordHash, @Param("organizationId") UUID organizationId,
                       @Param("at") OffsetDateTime at);

    /**
     * 自助入驻建的账号（V012）：登录名、口令由申请人自己设，手机号是验证过的联系人手机号；默认机构就是新建的那家。
     * 登录名被占时什么都不写，返回 null；手机号是否被占由服务先查（uq_identity_user_mobile）。
     */
    @Select("""
            INSERT INTO identity_user (login_name, display_name, password_hash, mobile, default_organization_id,
                                       created_at, updated_at)
            VALUES (#{loginName}, #{displayName}, #{passwordHash}, #{mobile}, #{organizationId}, #{at}, #{at})
            ON CONFLICT (login_name) DO NOTHING
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertSignupAccount(@Param("loginName") String loginName, @Param("displayName") String displayName,
                             @Param("passwordHash") String passwordHash, @Param("mobile") String mobile,
                             @Param("organizationId") UUID organizationId, @Param("at") OffsetDateTime at);

    @Select("SELECT count(*) FROM identity_user WHERE mobile = #{mobile}")
    long countAccountsByMobile(@Param("mobile") String mobile);

    @Select("SELECT count(*) FROM identity_user WHERE login_name = #{loginName}")
    long countAccountsByLoginName(@Param("loginName") String loginName);

    @Insert("""
            INSERT INTO identity_organization_member (organization_id, user_id, created_at, updated_at)
            VALUES (#{organizationId}, #{accountId}, #{at}, #{at})
            ON CONFLICT (organization_id, user_id) DO UPDATE SET member_status = 'ACTIVE', updated_at = #{at}
            """)
    int insertMember(@Param("organizationId") UUID organizationId, @Param("accountId") UUID accountId,
                     @Param("at") OffsetDateTime at);

    /**
     * 授一个角色（organizationId 为空是全局角色）。已经有效时不变；以前收回过的重新生效。
     * 唯一索引 uk_identity_user_role_scope 按 (user_id, role_id, COALESCE(organization_id, 全零)) 建的，这里照它推断冲突。
     */
    @Insert("""
            INSERT INTO identity_user_role (user_id, role_id, organization_id, valid_from)
            SELECT #{accountId}, r.id, #{organizationId}, #{at}
              FROM identity_role r
             WHERE r.role_code = #{roleCode}
               AND r.status = 'ACTIVE'
            ON CONFLICT (user_id, role_id, COALESCE(organization_id, '00000000-0000-0000-0000-000000000000'::uuid))
            DO UPDATE SET valid_from = EXCLUDED.valid_from, valid_until = NULL
             WHERE identity_user_role.valid_until IS NOT NULL
               AND identity_user_role.valid_until <= EXCLUDED.valid_from
            """)
    int grantRole(@Param("accountId") UUID accountId, @Param("roleCode") String roleCode,
                  @Param("organizationId") UUID organizationId, @Param("at") OffsetDateTime at);

    /** 收回一个角色：只标结束时间，记录保留。 */
    @Update("""
            UPDATE identity_user_role ur
            SET valid_until = #{at}
            FROM identity_role r
            WHERE r.id = ur.role_id
              AND r.role_code = #{roleCode}
              AND ur.user_id = #{accountId}
              AND ur.organization_id IS NOT DISTINCT FROM CAST(#{organizationId} AS uuid)
              AND ur.valid_from < #{at}
              AND (ur.valid_until IS NULL OR ur.valid_until > #{at})
            """)
    int revokeRole(@Param("accountId") UUID accountId, @Param("roleCode") String roleCode,
                   @Param("organizationId") UUID organizationId, @Param("at") OffsetDateTime at);

    /** 角色变了：令牌版本加一，这个账号手上的旧令牌立刻失效，重新登录才拿到新角色。 */
    @Update("""
            UPDATE identity_user
            SET token_version = token_version + 1,
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{accountId}
            """)
    int bumpTokenVersion(@Param("accountId") UUID accountId, @Param("at") OffsetDateTime at);

    // ---- 操作记录 ----

    @Insert("""
            INSERT INTO identity_organization_operation (
                organization_id, action, detail, operator_id, operator_name, occurred_at
            )
            VALUES (#{organizationId}, #{action}, #{detail}, #{operatorId}, #{operatorName}, #{at})
            """)
    int insertOperation(@Param("organizationId") UUID organizationId, @Param("action") String action,
                        @Param("detail") String detail, @Param("operatorId") UUID operatorId,
                        @Param("operatorName") String operatorName, @Param("at") OffsetDateTime at);

    @Select("""
            SELECT id, action, detail, operator_name, occurred_at
              FROM identity_organization_operation
             WHERE organization_id = #{organizationId}
             ORDER BY occurred_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<OperationRow> findOperations(@Param("organizationId") UUID organizationId, @Param("limit") int limit);

    /**
     * 有某个机构角色（直播主办 LIVE_HOST）的在用经营机构，和机构里有这个角色的在用账号数（S3-1 运营端“直播间”）。
     * 机构角色只在账号的默认机构下生效，规则和 findAccountRoles 一样。按机构名称排。
     */
    @Select("""
            SELECT o.id AS organization_id,
                   o.organization_name,
                   o.city_code,
                   count(DISTINCT u.id) AS account_count
              FROM identity_organization o
              JOIN identity_user u ON u.default_organization_id = o.id AND u.status = 'ACTIVE'
              JOIN identity_user_role ur ON ur.user_id = u.id
                   AND (ur.organization_id IS NULL OR ur.organization_id = o.id)
                   AND ur.valid_from <= #{at}
                   AND (ur.valid_until IS NULL OR ur.valid_until > #{at})
              JOIN identity_role r ON r.id = ur.role_id AND r.status = 'ACTIVE' AND r.role_code = #{roleCode}
             WHERE o.organization_type = 'PROVIDER'
               AND o.status = 'ACTIVE'
             GROUP BY o.id, o.organization_name, o.city_code
             ORDER BY o.organization_name, o.id
             LIMIT #{limit}
            """)
    List<RoleHolderRow> findRoleHolderOrganizations(@Param("roleCode") String roleCode, @Param("at") OffsetDateTime at,
                                                    @Param("limit") int limit);

    record RoleHolderRow(UUID organizationId, String organizationName, String cityCode, long accountCount) {
    }

    record OrganizationRow(UUID organizationId, String organizationCode, String organizationName,
                           String organizationType, String cityCode, String status,
                           OffsetDateTime qualificationReviewedAt, OffsetDateTime createdAt, int version,
                           String qualificationStatus) {
    }

    record QualificationRow(UUID organizationId, String subjectName, String licenseNumber, String businessScope,
                            String qualificationNote, String status, OffsetDateTime submittedAt,
                            OffsetDateTime reviewedAt, String rejectReason, int version) {
    }

    record AttachmentRow(UUID id, UUID organizationId, String fileName, String contentType, long sizeBytes,
                         String sha256, String storageKey, OffsetDateTime uploadedAt) {
    }

    record AccountRow(UUID accountId, String loginName, String displayName, String status,
                      OffsetDateTime createdAt) {
    }

    record AccountRoleRow(UUID accountId, String roleCode) {
    }

    record OperationRow(UUID id, String action, String detail, String operatorName, OffsetDateTime occurredAt) {
    }
}
