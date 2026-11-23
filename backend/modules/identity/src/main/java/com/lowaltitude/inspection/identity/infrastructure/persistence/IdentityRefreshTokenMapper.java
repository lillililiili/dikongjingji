package com.lowaltitude.inspection.identity.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Refresh tokens (V008). Bound parameters only; "now" comes from the caller. */
public interface IdentityRefreshTokenMapper {

    @Insert("""
            INSERT INTO identity_refresh_token (
                id, family_id, user_id, token_hash, token_version, status,
                issued_at, expires_at, family_expires_at, created_at, updated_at
            )
            VALUES (
                #{id}, #{familyId}, #{userId}, #{tokenHash}, #{tokenVersion}, 'ACTIVE',
                #{issuedAt}, #{expiresAt}, #{familyExpiresAt}, #{issuedAt}, #{issuedAt}
            )
            """)
    int insertRefreshToken(
            @Param("id") UUID id,
            @Param("familyId") UUID familyId,
            @Param("userId") UUID userId,
            @Param("tokenHash") String tokenHash,
            @Param("tokenVersion") int tokenVersion,
            @Param("issuedAt") OffsetDateTime issuedAt,
            @Param("expiresAt") OffsetDateTime expiresAt,
            @Param("familyExpiresAt") OffsetDateTime familyExpiresAt
    );

    @Select("""
            SELECT id, family_id, user_id, token_hash, token_version, status,
                   issued_at, expires_at, family_expires_at
              FROM identity_refresh_token
             WHERE token_hash = #{tokenHash}
            """)
    Optional<PersistedRefreshToken> findRefreshTokenByHash(@Param("tokenHash") String tokenHash);

    /** Only a still-active token is exchanged: of two requests with the same token, one wins. */
    @Update("""
            UPDATE identity_refresh_token
               SET status = 'ROTATED',
                   rotated_at = #{at},
                   replaced_by = #{replacedBy},
                   version = version + 1,
                   updated_at = #{at}
             WHERE id = #{id}
               AND status = 'ACTIVE'
            """)
    int markRefreshTokenRotated(
            @Param("id") UUID id,
            @Param("replacedBy") UUID replacedBy,
            @Param("at") OffsetDateTime at
    );

    @Update("""
            UPDATE identity_refresh_token
               SET status = 'REVOKED',
                   revoked_at = #{at},
                   revoke_reason = #{reason},
                   version = version + 1,
                   updated_at = #{at}
             WHERE family_id = #{familyId}
               AND status = 'ACTIVE'
            """)
    int revokeRefreshTokenFamily(
            @Param("familyId") UUID familyId,
            @Param("reason") String reason,
            @Param("at") OffsetDateTime at
    );
}
