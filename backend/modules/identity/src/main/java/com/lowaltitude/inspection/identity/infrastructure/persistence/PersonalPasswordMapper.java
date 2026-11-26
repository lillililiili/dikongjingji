package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PersonalPasswordMapper {
    record Credential(UUID id, String mobile, String passwordHash, boolean eligible) {
        public boolean hasPassword() {
            return eligible && !"!".equals(passwordHash);
        }
    }

    String COLUMNS = """
            SELECT u.id, u.mobile, u.password_hash,
                (u.status = 'ACTIVE' AND u.registered_via = 'PHONE' AND u.mobile IS NOT NULL
                 AND EXISTS (SELECT 1 FROM identity_organization o JOIN identity_organization_member m
                             ON m.organization_id = o.id AND m.user_id = u.id AND m.member_status = 'ACTIVE'
                             WHERE o.id = u.default_organization_id AND o.status = 'ACTIVE' AND o.organization_type = 'CUSTOMER')
                 AND COALESCE((SELECT bool_and(r.role_code = 'CUSTOMER' AND r.scope_type = 'GLOBAL' AND ur.organization_id IS NULL)
                               FROM identity_user_role ur JOIN identity_role r ON r.id = ur.role_id
                               WHERE ur.user_id = u.id AND r.status = 'ACTIVE' AND ur.valid_from <= statement_timestamp()
                                 AND (ur.valid_until IS NULL OR ur.valid_until > statement_timestamp())), false)) AS eligible
              FROM identity_user u
            """;

    // Read roles in a new statement after the lock is acquired: another transaction may have
    // committed a role change while this one was waiting for the account row.
    @Select("SELECT id FROM identity_user WHERE id = #{id} FOR UPDATE")
    UUID lockId(@Param("id") UUID id);

    @Select("SELECT id FROM identity_user WHERE mobile = #{phone} FOR UPDATE")
    UUID lockMobile(@Param("phone") String phone);

    @Select(COLUMNS + " WHERE u.id = #{id}")
    Credential credential(@Param("id") UUID id);

    default Credential lockById(UUID id) {
        UUID locked = lockId(id);
        return locked == null ? null : credential(locked);
    }

    default Credential lockByPhone(String phone) {
        UUID locked = lockMobile(phone);
        return locked == null ? null : credential(locked);
    }

    @Select("""
            SELECT COALESCE(password_setup_until > #{at}, false)
            FROM identity_login_session WHERE id = #{session} AND user_id = #{id} AND revoked_at IS NULL FOR UPDATE
            """)
    Boolean lockSessionProof(@Param("id") UUID id, @Param("session") UUID session, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE identity_sms_code SET setup_session_id = #{session}
            WHERE phone = #{phone} AND purpose = 'PASSWORD_SETUP' AND consumed_at IS NULL AND user_id = #{id}
            """)
    int bindSetupCode(@Param("id") UUID id, @Param("phone") String phone, @Param("session") UUID session);

    @Select("""
            SELECT EXISTS (SELECT 1 FROM identity_sms_code WHERE phone = #{phone} AND purpose = 'PASSWORD_SETUP'
                AND user_id = #{id} AND setup_session_id = #{session} AND consumed_at IS NULL AND expires_at > #{at})
            """)
    boolean hasSessionCode(@Param("id") UUID id, @Param("phone") String phone, @Param("session") UUID session,
                           @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE identity_user SET password_hash = #{hash}, updated_at = #{at}, version = version + 1
            WHERE id = #{id} AND password_hash = '!'
            """)
    int setFirstPassword(@Param("id") UUID id, @Param("hash") String hash, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE identity_user SET password_hash = #{hash}, token_version = token_version + 1,
                version = version + 1, updated_at = #{at} WHERE id = #{id}
            """)
    void replacePassword(@Param("id") UUID id, @Param("hash") String hash, @Param("at") OffsetDateTime at);

    record AttemptWindow(int failedCount, OffsetDateTime windowStartedAt, OffsetDateTime lockedUntil) {}

    @Insert("""
            INSERT INTO identity_password_guard (phone, window_started_at) VALUES (#{phone}, #{at})
            ON CONFLICT (phone) DO NOTHING
            """)
    void ensureAttempts(@Param("phone") String phone, @Param("at") OffsetDateTime at);

    @Select("SELECT failed_count, window_started_at, locked_until FROM identity_password_guard WHERE phone = #{phone} FOR UPDATE")
    AttemptWindow lockAttempts(@Param("phone") String phone);

    @Update("""
            UPDATE identity_password_guard SET failed_count = #{count}, window_started_at = #{start},
                locked_until = #{until} WHERE phone = #{phone}
            """)
    void saveAttempts(@Param("phone") String phone, @Param("count") int count, @Param("start") OffsetDateTime start,
                      @Param("until") OffsetDateTime until);
}
