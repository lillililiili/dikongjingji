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
 * 手机号登录、登录会话、注销、个人信息导出、企业组织（S5-7、S5-8，迁移 V009、V010）的读写。
 * 全部用绑定参数；“当前时间”由调用方传入；不删除任何行（作废、撤销、退出都只改状态）。
 * 读账号不读口令摘要；手机号只在本人相关的语句里读，给别人看时由服务打码。
 */
public interface AccountMapper {

    // ---------------------------------------------------------------- 短信验证码

    @Insert("""
            INSERT INTO identity_sms_code (phone, purpose, user_id, code_hash, sent_at, expires_at)
            VALUES (#{phone}, #{purpose}, #{userId}, #{codeHash}, #{at}, #{expiresAt})
            """)
    int insertSmsCode(@Param("phone") String phone, @Param("purpose") String purpose, @Param("userId") UUID userId,
                      @Param("codeHash") String codeHash, @Param("at") OffsetDateTime at,
                      @Param("expiresAt") OffsetDateTime expiresAt);

    /** 发新验证码时，同一号码同一用途还没用过的旧码作废（只认最新一条）。 */
    @Update("""
            UPDATE identity_sms_code
               SET consumed_at = #{at}
             WHERE phone = #{phone}
               AND purpose = #{purpose}
               AND consumed_at IS NULL
            """)
    int retireSmsCodes(@Param("phone") String phone, @Param("purpose") String purpose, @Param("at") OffsetDateTime at);

    @Select("""
            SELECT max(sent_at)
              FROM identity_sms_code
             WHERE phone = #{phone}
               AND purpose = #{purpose}
            """)
    OffsetDateTime findLastSentAt(@Param("phone") String phone, @Param("purpose") String purpose);

    /** 这个号码从 since 起真正发出去的短信条数（换号凭据不是短信，不算）。 */
    @Select("""
            SELECT count(*)
              FROM identity_sms_code
             WHERE phone = #{phone}
               AND purpose <> 'CHANGE_TICKET'
               AND sent_at >= #{since}
            """)
    int countSentSince(@Param("phone") String phone, @Param("since") OffsetDateTime since);

    /** 最新一条还能用的验证码（没用过、没过期、错得不够多），锁住防并发重复使用。 */
    @Select("""
            SELECT id, user_id, code_hash, failed_attempts, expires_at
              FROM identity_sms_code
             WHERE phone = #{phone}
               AND purpose = #{purpose}
               AND consumed_at IS NULL
               AND expires_at > #{at}
               AND failed_attempts < #{maxAttempts}
             ORDER BY sent_at DESC
             LIMIT 1
             FOR UPDATE
            """)
    SmsCodeRow lockUsableSmsCode(@Param("phone") String phone, @Param("purpose") String purpose,
                                 @Param("at") OffsetDateTime at, @Param("maxAttempts") int maxAttempts);

    @Update("UPDATE identity_sms_code SET failed_attempts = failed_attempts + 1 WHERE id = #{id}")
    int markSmsCodeFailed(@Param("id") UUID id);

    @Update("UPDATE identity_sms_code SET consumed_at = #{at} WHERE id = #{id} AND consumed_at IS NULL")
    int consumeSmsCode(@Param("id") UUID id, @Param("at") OffsetDateTime at);

    @Insert("""
            INSERT INTO identity_phone_guard (phone, failed_count, window_started_at, updated_at)
            VALUES (#{phone}, 0, #{at}, #{at})
            ON CONFLICT (phone) DO NOTHING
            """)
    int ensurePhoneGuard(@Param("phone") String phone, @Param("at") OffsetDateTime at);

    @Select("""
            SELECT failed_count, window_started_at, locked_until
              FROM identity_phone_guard
             WHERE phone = #{phone}
               FOR UPDATE
            """)
    PhoneGuardRow lockPhoneGuard(@Param("phone") String phone);

    @Select("SELECT locked_until FROM identity_phone_guard WHERE phone = #{phone}")
    OffsetDateTime findPhoneLockedUntil(@Param("phone") String phone);

    @Update("""
            UPDATE identity_phone_guard
               SET failed_count = #{failedCount},
                   window_started_at = #{windowStartedAt},
                   locked_until = #{lockedUntil},
                   updated_at = #{at}
             WHERE phone = #{phone}
            """)
    int updatePhoneGuard(@Param("phone") String phone, @Param("failedCount") int failedCount,
                         @Param("windowStartedAt") OffsetDateTime windowStartedAt,
                         @Param("lockedUntil") OffsetDateTime lockedUntil, @Param("at") OffsetDateTime at);

    /** 本机模拟短信的发件箱（只有 fake 短信服务写）。 */
    @Insert("""
            INSERT INTO identity_dev_sms_outbox (phone, content, sent_at)
            VALUES (#{phone}, #{content}, #{at})
            """)
    int insertDevSms(@Param("phone") String phone, @Param("content") String content, @Param("at") OffsetDateTime at);

    // ---------------------------------------------------------------- 账号

    String USER_COLUMNS = """
            SELECT u.id, u.status, u.display_name, u.mobile, u.acting_organization_id, u.default_organization_id,
                   u.registered_via
              FROM identity_user u
            """;

    @Select(USER_COLUMNS + " WHERE u.mobile = #{phone}")
    UserRow findUserByMobile(@Param("phone") String phone);

    @Select(USER_COLUMNS + " WHERE u.id = #{userId}")
    UserRow findUser(@Param("userId") UUID userId);

    @Select(USER_COLUMNS + " WHERE u.id = #{userId} FOR UPDATE")
    UserRow lockUser(@Param("userId") UUID userId);

    /** 验证码注册的个人账号各自一家个人客户机构（登录要有默认机构；订单、报名都按账号归属，不看这家机构）。 */
    @Select("""
            INSERT INTO identity_organization (organization_code, organization_name, organization_type, created_at, updated_at)
            VALUES (#{code}, #{name}, 'CUSTOMER', #{at}, #{at})
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertCustomerOrganization(@Param("code") String code, @Param("name") String name,
                                    @Param("at") OffsetDateTime at);

    /** 手机号已被别的账号占用时什么都不写，返回 null。口令摘要是不能通过校验的“!”：这种账号只能用验证码登录。 */
    @Select("""
            INSERT INTO identity_user (login_name, display_name, password_hash, default_organization_id, mobile,
                                       registered_via, created_at, updated_at)
            VALUES (#{loginName}, #{displayName}, '!', #{organizationId}, #{phone}, 'PHONE', #{at}, #{at})
            ON CONFLICT (mobile) WHERE mobile IS NOT NULL DO NOTHING
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertPhoneUser(@Param("loginName") String loginName, @Param("displayName") String displayName,
                         @Param("organizationId") UUID organizationId, @Param("phone") String phone,
                         @Param("at") OffsetDateTime at);

    @Insert("""
            INSERT INTO identity_organization_member (organization_id, user_id, created_at, updated_at)
            VALUES (#{organizationId}, #{userId}, #{at}, #{at})
            ON CONFLICT (organization_id, user_id) DO UPDATE SET member_status = 'ACTIVE', updated_at = #{at}
            """)
    int insertOrganizationMember(@Param("organizationId") UUID organizationId, @Param("userId") UUID userId,
                                 @Param("at") OffsetDateTime at);

    /** 新注册账号授全局的客户角色。 */
    @Insert("""
            INSERT INTO identity_user_role (user_id, role_id, organization_id, valid_from)
            SELECT #{userId}, r.id, NULL, #{at}
              FROM identity_role r
             WHERE r.role_code = 'CUSTOMER'
               AND r.status = 'ACTIVE'
            ON CONFLICT (user_id, role_id, COALESCE(organization_id, '00000000-0000-0000-0000-000000000000'::uuid))
            DO NOTHING
            """)
    int grantCustomerRole(@Param("userId") UUID userId, @Param("at") OffsetDateTime at);

    @Select("""
            SELECT count(*) > 0
              FROM identity_user_role ur
              JOIN identity_role r ON r.id = ur.role_id
             WHERE ur.user_id = #{userId}
               AND r.role_code = 'CUSTOMER'
               AND r.status = 'ACTIVE'
               AND ur.organization_id IS NULL
               AND ur.valid_from <= #{at}
               AND (ur.valid_until IS NULL OR ur.valid_until > #{at})
            """)
    boolean hasCustomerRole(@Param("userId") UUID userId, @Param("at") OffsetDateTime at);

    @Update("UPDATE identity_user SET mobile = #{phone}, updated_at = #{at} WHERE id = #{userId}")
    int updateMobile(@Param("userId") UUID userId, @Param("phone") String phone, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE identity_user
               SET acting_organization_id = #{organizationId}, updated_at = #{at}
             WHERE id = #{userId}
            """)
    int updateActingOrganization(@Param("userId") UUID userId, @Param("organizationId") UUID organizationId,
                                 @Param("at") OffsetDateTime at);

    /** 当前身份是这家企业的账号回到个人身份（被移除、退出、企业解散）。 */
    @Update("""
            UPDATE identity_user
               SET acting_organization_id = NULL, updated_at = #{at}
             WHERE acting_organization_id = #{organizationId}
               AND (CAST(#{userId} AS uuid) IS NULL OR id = #{userId})
            """)
    int clearActingOrganization(@Param("organizationId") UUID organizationId, @Param("userId") UUID userId,
                                @Param("at") OffsetDateTime at);

    /**
     * 注销到期的匿名化：显示名改“已注销用户”，登录名换成不可猜的占位，口令不可用，手机号清空，当前身份回到个人，
     * 状态 CLOSED（不能再登录），令牌版本加一（手上的令牌立刻失效）。账号行保留，订单等业务数据仍能关联，但不再对应到人。
     */
    @Update("""
            UPDATE identity_user
               SET display_name = '已注销用户',
                   login_name = #{loginName},
                   password_hash = '!',
                   mobile = NULL,
                   mobile_ciphertext = NULL,
                   acting_organization_id = NULL,
                   status = 'CLOSED',
                   closed_at = #{at},
                   token_version = token_version + 1,
                   version = version + 1,
                   updated_at = #{at}
             WHERE id = #{userId}
               AND status <> 'CLOSED'
            """)
    int anonymizeUser(@Param("userId") UUID userId, @Param("loginName") String loginName,
                      @Param("at") OffsetDateTime at);

    @Select("""
            <script>
            SELECT id, display_name
              FROM identity_user
             WHERE id IN
            <foreach collection="userIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<NameRow> findDisplayNames(@Param("userIds") List<UUID> userIds);

    // ---------------------------------------------------------------- 登录会话

    /** 同一台设备再登录：上一次的会话作废（REPLACED），带旧 sid 的令牌随之失效。 */
    @Update("""
            UPDATE identity_login_session
               SET revoked_at = #{at}, revoke_reason = 'REPLACED'
             WHERE user_id = #{userId}
               AND device_key = #{deviceKey}
               AND revoked_at IS NULL
            """)
    int replaceDeviceSessions(@Param("userId") UUID userId, @Param("deviceKey") String deviceKey,
                              @Param("at") OffsetDateTime at);

    @Select("""
            INSERT INTO identity_login_session (user_id, device_key, device_name, platform, city_code, login_method,
                                                signed_in_at, last_active_at)
            VALUES (#{userId}, #{deviceKey}, #{deviceName}, #{platform}, #{cityCode}, #{loginMethod}, #{at}, #{at})
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertSession(@Param("userId") UUID userId, @Param("deviceKey") String deviceKey,
                       @Param("deviceName") String deviceName, @Param("platform") String platform,
                       @Param("cityCode") String cityCode, @Param("loginMethod") String loginMethod,
                       @Param("at") OffsetDateTime at);

    @Select("""
            SELECT count(*) > 0
              FROM identity_login_session
             WHERE id = #{sessionId}
               AND user_id = #{userId}
               AND revoked_at IS NULL
            """)
    boolean isSessionActive(@Param("sessionId") UUID sessionId, @Param("userId") UUID userId);

    @Select("""
            SELECT id, device_name, platform, city_code, signed_in_at, last_active_at
              FROM identity_login_session
             WHERE user_id = #{userId}
               AND revoked_at IS NULL
               AND last_active_at > #{since}
             ORDER BY last_active_at DESC, id DESC
             LIMIT #{limit}
            """)
    List<SessionRow> findActiveSessions(@Param("userId") UUID userId, @Param("since") OffsetDateTime since,
                                        @Param("limit") int limit);

    @Select("""
            SELECT id, device_name, platform, city_code, signed_in_at, last_active_at
              FROM identity_login_session
             WHERE id = #{sessionId}
               AND user_id = #{userId}
            """)
    SessionRow findSession(@Param("sessionId") UUID sessionId, @Param("userId") UUID userId);

    @Update("""
            UPDATE identity_login_session
               SET revoked_at = #{at}, revoke_reason = #{reason}
             WHERE id = #{sessionId}
               AND user_id = #{userId}
               AND revoked_at IS NULL
            """)
    int revokeSession(@Param("sessionId") UUID sessionId, @Param("userId") UUID userId,
                      @Param("reason") String reason, @Param("at") OffsetDateTime at);

    /** 除 keepSessionId 外全部退出；keepSessionId 为空时全部退出。 */
    @Update("""
            UPDATE identity_login_session
               SET revoked_at = #{at}, revoke_reason = #{reason}
             WHERE user_id = #{userId}
               AND revoked_at IS NULL
               AND (CAST(#{keepSessionId} AS uuid) IS NULL OR id <> #{keepSessionId})
            """)
    int revokeOtherSessions(@Param("userId") UUID userId, @Param("keepSessionId") UUID keepSessionId,
                            @Param("reason") String reason, @Param("at") OffsetDateTime at);

    // ---------------------------------------------------------------- 企业

    String ENTERPRISE_COLUMNS = """
            SELECT e.organization_id, o.organization_name, e.credit_code, o.city_code, o.qualification_reviewed_at,
                   m.member_role,
                   (SELECT count(*) FROM identity_enterprise_member c
                     WHERE c.organization_id = e.organization_id AND c.status = 'ACTIVE') AS member_count,
                   e.created_at
              FROM identity_enterprise e
              JOIN identity_organization o ON o.id = e.organization_id
              JOIN identity_enterprise_member m
                ON m.organization_id = e.organization_id
               AND m.user_id = #{userId}
               AND m.status = 'ACTIVE'
             WHERE e.status = 'ACTIVE'
               AND o.status = 'ACTIVE'
            """;

    @Select(ENTERPRISE_COLUMNS + " ORDER BY m.joined_at, e.organization_id")
    List<EnterpriseRow> findMyEnterprises(@Param("userId") UUID userId);

    @Select(ENTERPRISE_COLUMNS + " AND e.organization_id = #{organizationId}")
    EnterpriseRow findMyEnterprise(@Param("userId") UUID userId, @Param("organizationId") UUID organizationId);

    @Select("""
            SELECT organization_id
              FROM identity_enterprise
             WHERE created_by = #{userId}
               AND create_key = #{createKey}
            """)
    UUID findEnterpriseByCreateKey(@Param("userId") UUID userId, @Param("createKey") UUID createKey);

    @Select("""
            SELECT credit_code, o.organization_name
              FROM identity_enterprise e
              JOIN identity_organization o ON o.id = e.organization_id
             WHERE e.organization_id = #{organizationId}
            """)
    EnterpriseCreateRow findEnterpriseCreateFacts(@Param("organizationId") UUID organizationId);

    @Select("""
            SELECT count(*) > 0
              FROM identity_enterprise
             WHERE credit_code = #{creditCode}
               AND status = 'ACTIVE'
            """)
    boolean isCreditCodeTaken(@Param("creditCode") String creditCode);

    @Select("""
            SELECT count(*)
              FROM identity_enterprise_member m
              JOIN identity_enterprise e ON e.organization_id = m.organization_id AND e.status = 'ACTIVE'
             WHERE m.user_id = #{userId}
               AND m.status = 'ACTIVE'
            """)
    int countMyMemberships(@Param("userId") UUID userId);

    @Select("""
            INSERT INTO identity_organization (organization_code, organization_name, organization_type, city_code,
                                               created_at, updated_at)
            VALUES (#{code}, #{name}, 'CUSTOMER', #{cityCode}, #{at}, #{at})
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertEnterpriseOrganization(@Param("code") String code, @Param("name") String name,
                                      @Param("cityCode") String cityCode, @Param("at") OffsetDateTime at);

    @Insert("""
            INSERT INTO identity_enterprise (organization_id, credit_code, created_by, create_key, created_at, updated_at)
            VALUES (#{organizationId}, #{creditCode}, #{userId}, #{createKey}, #{at}, #{at})
            """)
    int insertEnterprise(@Param("organizationId") UUID organizationId, @Param("creditCode") String creditCode,
                         @Param("userId") UUID userId, @Param("createKey") UUID createKey,
                         @Param("at") OffsetDateTime at);

    @Insert("""
            INSERT INTO identity_enterprise_member (organization_id, user_id, member_role, joined_at, created_at, updated_at)
            VALUES (#{organizationId}, #{userId}, #{role}, #{at}, #{at}, #{at})
            """)
    int insertEnterpriseMember(@Param("organizationId") UUID organizationId, @Param("userId") UUID userId,
                               @Param("role") String role, @Param("at") OffsetDateTime at);

    /** 锁住一家有效企业（改成员、接受邀请时防并发超员或删掉最后一名管理员）；不存在或已解散为 null。 */
    @Select("""
            SELECT organization_id
              FROM identity_enterprise
             WHERE organization_id = #{organizationId}
               AND status = 'ACTIVE'
               FOR UPDATE
            """)
    UUID lockEnterprise(@Param("organizationId") UUID organizationId);

    @Select("""
            SELECT m.user_id, u.display_name, u.mobile, m.member_role, m.joined_at
              FROM identity_enterprise_member m
              JOIN identity_user u ON u.id = m.user_id
             WHERE m.organization_id = #{organizationId}
               AND m.status = 'ACTIVE'
             ORDER BY CASE m.member_role WHEN 'ADMIN' THEN 0 ELSE 1 END, m.joined_at, m.user_id
            """)
    List<MemberRow> findMembers(@Param("organizationId") UUID organizationId);

    @Select("""
            SELECT member_role
              FROM identity_enterprise_member
             WHERE organization_id = #{organizationId}
               AND user_id = #{userId}
               AND status = 'ACTIVE'
            """)
    String findMemberRole(@Param("organizationId") UUID organizationId, @Param("userId") UUID userId);

    @Select("""
            SELECT count(*)
              FROM identity_enterprise_member
             WHERE organization_id = #{organizationId}
               AND status = 'ACTIVE'
               AND (CAST(#{role} AS varchar) IS NULL OR member_role = #{role})
            """)
    int countMembers(@Param("organizationId") UUID organizationId, @Param("role") String role);

    @Update("""
            UPDATE identity_enterprise_member
               SET member_role = #{role}, version = version + 1, updated_at = #{at}
             WHERE organization_id = #{organizationId}
               AND user_id = #{userId}
               AND status = 'ACTIVE'
            """)
    int updateMemberRole(@Param("organizationId") UUID organizationId, @Param("userId") UUID userId,
                         @Param("role") String role, @Param("at") OffsetDateTime at);

    /** 退出（LEFT）或被移除（REMOVED）：只改状态，记录保留。 */
    @Update("""
            UPDATE identity_enterprise_member
               SET status = #{status}, ended_at = #{at}, ended_by = #{endedBy}, version = version + 1, updated_at = #{at}
             WHERE organization_id = #{organizationId}
               AND user_id = #{userId}
               AND status = 'ACTIVE'
            """)
    int endMembership(@Param("organizationId") UUID organizationId, @Param("userId") UUID userId,
                      @Param("status") String status, @Param("endedBy") UUID endedBy, @Param("at") OffsetDateTime at);

    /** 企业只剩本人时退出即解散：企业和它的机构都停用，待确认的邀请撤销。 */
    @Update("""
            UPDATE identity_enterprise
               SET status = 'DISSOLVED', version = version + 1, updated_at = #{at}
             WHERE organization_id = #{organizationId}
               AND status = 'ACTIVE'
            """)
    int dissolveEnterprise(@Param("organizationId") UUID organizationId, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE identity_organization
               SET status = 'DISABLED', version = version + 1, updated_at = #{at}
             WHERE id = #{organizationId}
               AND organization_type = 'CUSTOMER'
            """)
    int disableOrganization(@Param("organizationId") UUID organizationId, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE identity_enterprise_invitation
               SET status = 'REVOKED', version = version + 1
             WHERE organization_id = #{organizationId}
               AND status = 'PENDING'
            """)
    int revokeAllInvitations(@Param("organizationId") UUID organizationId);

    /** 本人当前身份所在的企业（还有效、本人还在其中）；不是企业身份或已失效时为 null。 */
    @Select("""
            SELECT e.organization_id, o.organization_name, m.member_role
              FROM identity_user u
              JOIN identity_enterprise e ON e.organization_id = u.acting_organization_id AND e.status = 'ACTIVE'
              JOIN identity_organization o ON o.id = e.organization_id AND o.status = 'ACTIVE'
              JOIN identity_enterprise_member m
                ON m.organization_id = e.organization_id AND m.user_id = u.id AND m.status = 'ACTIVE'
             WHERE u.id = #{userId}
            """)
    ActingRow findActingEnterprise(@Param("userId") UUID userId);

    // ---------------------------------------------------------------- 邀请

    String INVITATION_COLUMNS = """
            SELECT i.id, i.organization_id, o.organization_name, o.qualification_reviewed_at, i.invite_kind,
                   i.invitee_phone, i.member_role, i.invited_by, inviter.display_name AS invited_by_name,
                   i.status, i.sent_at, i.expires_at
              FROM identity_enterprise_invitation i
              JOIN identity_organization o ON o.id = i.organization_id
              JOIN identity_user inviter ON inviter.id = i.invited_by
            """;

    @Select(INVITATION_COLUMNS + """
             WHERE i.organization_id = #{organizationId}
               AND i.status = 'PENDING'
               AND i.expires_at > #{at}
             ORDER BY i.sent_at DESC, i.id
            """)
    List<InvitationRow> findPendingInvitations(@Param("organizationId") UUID organizationId,
                                               @Param("at") OffsetDateTime at);

    @Select(INVITATION_COLUMNS + " WHERE i.id = #{invitationId}")
    InvitationRow findInvitation(@Param("invitationId") UUID invitationId);

    @Select(INVITATION_COLUMNS + " WHERE i.id = #{invitationId} FOR UPDATE OF i")
    InvitationRow lockInvitation(@Param("invitationId") UUID invitationId);

    @Select(INVITATION_COLUMNS + " WHERE i.id = #{invitationId} AND i.code_hash = #{codeHash}")
    InvitationRow findInvitationByCode(@Param("invitationId") UUID invitationId, @Param("codeHash") String codeHash);

    /** 发给这个号码、还没过期的待确认邀请（企业还有效）。 */
    @Select(INVITATION_COLUMNS + """
              JOIN identity_enterprise e ON e.organization_id = i.organization_id AND e.status = 'ACTIVE'
             WHERE i.invitee_phone = #{phone}
               AND i.status = 'PENDING'
               AND i.expires_at > #{at}
             ORDER BY i.sent_at DESC, i.id
            """)
    List<InvitationRow> findInvitationsForPhone(@Param("phone") String phone, @Param("at") OffsetDateTime at);

    @Select("""
            SELECT id
              FROM identity_enterprise_invitation
             WHERE organization_id = #{organizationId}
               AND invitee_phone = #{phone}
               AND status = 'PENDING'
            """)
    UUID findPendingPhoneInvitation(@Param("organizationId") UUID organizationId, @Param("phone") String phone);

    @Select("""
            SELECT count(*) > 0
              FROM identity_enterprise_member m
              JOIN identity_user u ON u.id = m.user_id
             WHERE m.organization_id = #{organizationId}
               AND m.status = 'ACTIVE'
               AND u.mobile = #{phone}
            """)
    boolean isPhoneMember(@Param("organizationId") UUID organizationId, @Param("phone") String phone);

    @Select("""
            INSERT INTO identity_enterprise_invitation (organization_id, invite_kind, invitee_phone, code_hash,
                                                        invited_by, sent_at, expires_at, created_at)
            VALUES (#{organizationId}, #{kind}, #{phone}, #{codeHash}, #{invitedBy}, #{at}, #{expiresAt}, #{at})
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertInvitation(@Param("organizationId") UUID organizationId, @Param("kind") String kind,
                          @Param("phone") String phone, @Param("codeHash") String codeHash,
                          @Param("invitedBy") UUID invitedBy, @Param("at") OffsetDateTime at,
                          @Param("expiresAt") OffsetDateTime expiresAt);

    /** 待确认的邀请过期了还占着“一个号码一条”的位置：重新邀请前把它记为撤销。 */
    @Update("""
            UPDATE identity_enterprise_invitation
               SET status = 'REVOKED', version = version + 1
             WHERE id = #{invitationId}
               AND status = 'PENDING'
            """)
    int revokeInvitation(@Param("invitationId") UUID invitationId);

    @Update("""
            UPDATE identity_enterprise_invitation
               SET sent_at = #{at}, expires_at = #{expiresAt}, version = version + 1
             WHERE id = #{invitationId}
               AND status = 'PENDING'
            """)
    int renewInvitation(@Param("invitationId") UUID invitationId, @Param("at") OffsetDateTime at,
                        @Param("expiresAt") OffsetDateTime expiresAt);

    @Update("""
            UPDATE identity_enterprise_invitation
               SET status = #{status}, responded_by = #{userId}, responded_at = #{at}, version = version + 1
             WHERE id = #{invitationId}
               AND status = 'PENDING'
            """)
    int respondInvitation(@Param("invitationId") UUID invitationId, @Param("status") String status,
                          @Param("userId") UUID userId, @Param("at") OffsetDateTime at);

    @Insert("""
            INSERT INTO identity_auth_audit (actor_user_id, action_code, target_type, target_id, organization_id,
                                             request_id, result_code, occurred_at)
            VALUES (#{actorId}, #{action}, #{targetType}, #{targetId}, #{organizationId}, #{requestId}, 'OK', #{at})
            """)
    int insertAudit(@Param("actorId") UUID actorId, @Param("action") String action,
                    @Param("targetType") String targetType, @Param("targetId") UUID targetId,
                    @Param("organizationId") UUID organizationId, @Param("requestId") String requestId,
                    @Param("at") OffsetDateTime at);

    // ---------------------------------------------------------------- 注销

    @Select("""
            SELECT id, user_id, requested_at, due_at
              FROM identity_closure_request
             WHERE user_id = #{userId}
               AND status = 'PENDING'
            """)
    ClosureRow findPendingClosure(@Param("userId") UUID userId);

    @Insert("""
            INSERT INTO identity_closure_request (user_id, status, requested_at, due_at)
            VALUES (#{userId}, 'PENDING', #{at}, #{dueAt})
            """)
    int insertClosure(@Param("userId") UUID userId, @Param("at") OffsetDateTime at,
                      @Param("dueAt") OffsetDateTime dueAt);

    @Update("""
            UPDATE identity_closure_request
               SET status = 'WITHDRAWN', withdrawn_at = #{at}
             WHERE id = #{closureId}
               AND status = 'PENDING'
            """)
    int withdrawClosure(@Param("closureId") UUID closureId, @Param("at") OffsetDateTime at);

    @Select("""
            SELECT id, user_id, requested_at, due_at
              FROM identity_closure_request
             WHERE status = 'PENDING'
               AND due_at <= #{at}
             ORDER BY due_at, id
             LIMIT #{limit}
            """)
    List<ClosureRow> findDueClosures(@Param("at") OffsetDateTime at, @Param("limit") int limit);

    @Select("""
            SELECT id, user_id, requested_at, due_at
              FROM identity_closure_request
             WHERE id = #{closureId}
               AND status = 'PENDING'
               FOR UPDATE
            """)
    ClosureRow lockPendingClosure(@Param("closureId") UUID closureId);

    @Update("""
            UPDATE identity_closure_request
               SET status = 'COMPLETED', completed_at = #{at}
             WHERE id = #{closureId}
               AND status = 'PENDING'
            """)
    int completeClosure(@Param("closureId") UUID closureId, @Param("at") OffsetDateTime at);

    // ---------------------------------------------------------------- 个人信息导出

    String EXPORT_COLUMNS = """
            SELECT id, user_id, sections, status, requested_at, ready_at, expires_at, storage_key, size_bytes
              FROM identity_data_export
            """;

    @Select(EXPORT_COLUMNS + " WHERE user_id = #{userId} ORDER BY requested_at DESC, id DESC LIMIT #{limit}")
    List<ExportRow> findExports(@Param("userId") UUID userId, @Param("limit") int limit);

    @Select(EXPORT_COLUMNS + " WHERE id = #{exportId} AND user_id = #{userId}")
    ExportRow findExport(@Param("exportId") UUID exportId, @Param("userId") UUID userId);

    @Select("SELECT count(*) FROM identity_data_export WHERE user_id = #{userId} AND status = 'PENDING'")
    int countPendingExports(@Param("userId") UUID userId);

    @Select("""
            INSERT INTO identity_data_export (user_id, sections, status, requested_at)
            VALUES (#{userId}, #{sections}, 'PENDING', #{at})
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertExport(@Param("userId") UUID userId, @Param("sections") String sections,
                      @Param("at") OffsetDateTime at);

    /** 等着生成的申请，最早的在前；SKIP LOCKED 让多个实例不会生成同一份。 */
    @Select(EXPORT_COLUMNS + """
             WHERE status = 'PENDING'
             ORDER BY requested_at, id
             LIMIT #{limit}
             FOR UPDATE SKIP LOCKED
            """)
    List<ExportRow> lockPendingExports(@Param("limit") int limit);

    @Update("""
            UPDATE identity_data_export
               SET status = 'READY', storage_key = #{storageKey}, size_bytes = #{sizeBytes}, sha256 = #{sha256},
                   ready_at = #{at}, expires_at = #{expiresAt}
             WHERE id = #{exportId}
               AND status = 'PENDING'
            """)
    int markExportReady(@Param("exportId") UUID exportId, @Param("storageKey") String storageKey,
                        @Param("sizeBytes") long sizeBytes, @Param("sha256") String sha256,
                        @Param("at") OffsetDateTime at, @Param("expiresAt") OffsetDateTime expiresAt);

    @Update("UPDATE identity_data_export SET status = 'FAILED' WHERE id = #{exportId} AND status = 'PENDING'")
    int markExportFailed(@Param("exportId") UUID exportId);

    @Update("""
            UPDATE identity_data_export
               SET download_token_hash = #{tokenHash}, download_token_expires_at = #{expiresAt}
             WHERE id = #{exportId}
               AND user_id = #{userId}
               AND status = 'READY'
            """)
    int setDownloadToken(@Param("exportId") UUID exportId, @Param("userId") UUID userId,
                         @Param("tokenHash") String tokenHash, @Param("expiresAt") OffsetDateTime expiresAt);

    /** 用掉下载凭据：对得上、没过期、副本还在时清掉凭据（只能用一次），下载次数加一，返回存储键；否则 null。 */
    @Select("""
            UPDATE identity_data_export
               SET download_token_hash = NULL, download_token_expires_at = NULL, download_count = download_count + 1
             WHERE id = #{exportId}
               AND status = 'READY'
               AND download_token_hash = #{tokenHash}
               AND download_token_expires_at > #{at}
               AND expires_at > #{at}
            RETURNING storage_key
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    String consumeDownloadToken(@Param("exportId") UUID exportId, @Param("tokenHash") String tokenHash,
                                @Param("at") OffsetDateTime at);

    @Select(EXPORT_COLUMNS + """
             WHERE status = 'READY'
               AND expires_at <= #{at}
             ORDER BY expires_at, id
             LIMIT #{limit}
            """)
    List<ExportRow> findExpiredExports(@Param("at") OffsetDateTime at, @Param("limit") int limit);

    @Update("""
            UPDATE identity_data_export
               SET status = 'EXPIRED', download_token_hash = NULL, download_token_expires_at = NULL
             WHERE id = #{exportId}
               AND status = 'READY'
            """)
    int markExportExpired(@Param("exportId") UUID exportId);

    // ---------------------------------------------------------------- 行

    record SmsCodeRow(UUID id, UUID userId, String codeHash, int failedAttempts, OffsetDateTime expiresAt) {
    }

    record PhoneGuardRow(int failedCount, OffsetDateTime windowStartedAt, OffsetDateTime lockedUntil) {
    }

    record UserRow(UUID id, String status, String displayName, String mobile, UUID actingOrganizationId,
                   UUID defaultOrganizationId, String registeredVia) {
    }

    record NameRow(UUID id, String displayName) {
    }

    record SessionRow(UUID id, String deviceName, String platform, String cityCode, OffsetDateTime signedInAt,
                      OffsetDateTime lastActiveAt) {
    }

    record EnterpriseRow(UUID organizationId, String organizationName, String creditCode, String cityCode,
                         OffsetDateTime qualificationReviewedAt, String memberRole, long memberCount,
                         OffsetDateTime createdAt) {
    }

    record EnterpriseCreateRow(String creditCode, String organizationName) {
    }

    record MemberRow(UUID userId, String displayName, String mobile, String memberRole, OffsetDateTime joinedAt) {
    }

    record ActingRow(UUID organizationId, String organizationName, String memberRole) {
    }

    record InvitationRow(UUID id, UUID organizationId, String organizationName, OffsetDateTime qualificationReviewedAt,
                         String inviteKind, String inviteePhone, String memberRole, UUID invitedBy,
                         String invitedByName, String status, OffsetDateTime sentAt, OffsetDateTime expiresAt) {
    }

    record ClosureRow(UUID id, UUID userId, OffsetDateTime requestedAt, OffsetDateTime dueAt) {
    }

    record ExportRow(UUID id, UUID userId, String sections, String status, OffsetDateTime requestedAt,
                     OffsetDateTime readyAt, OffsetDateTime expiresAt, String storageKey, Long sizeBytes) {
    }
}
