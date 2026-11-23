-- 登录续期（2026-11-22）：刷新令牌。访问令牌仍是 900 秒的 JWT；刷新令牌是随机串，只存它的 SHA-256。
-- 每次续期都换一个新的（轮换），旧的记为 ROTATED；同一次登录得到的一串刷新令牌是一个 family。
-- 已经换掉的旧令牌再被拿来续期，就当作被盗用：整个 family 撤销（REVOKED，原因 REUSE_DETECTED），这次登录要重新登录。
-- 退出登录撤销所在的 family（LOGOUT）；账号停用或令牌版本变了，续期时撤销（ACCOUNT_CHANGED）。
-- 单个刷新令牌 30 天有效，一次登录最长 90 天（family_expires_at），到期要重新登录。
CREATE TABLE identity_refresh_token (
    id uuid PRIMARY KEY,
    family_id uuid NOT NULL,
    user_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    token_hash varchar(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    token_version integer NOT NULL CHECK (token_version >= 0),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED')),
    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    family_expires_at timestamptz NOT NULL,
    rotated_at timestamptz,
    replaced_by uuid,
    revoked_at timestamptz,
    revoke_reason varchar(24)
        CHECK (revoke_reason IS NULL OR revoke_reason IN ('LOGOUT', 'REUSE_DETECTED', 'ACCOUNT_CHANGED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (expires_at > issued_at),
    CHECK (family_expires_at >= expires_at),
    CHECK ((status = 'ROTATED') = (rotated_at IS NOT NULL)),
    CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL))
);

-- 撤销一次登录时按 family 找还有效的令牌。
CREATE INDEX idx_identity_refresh_token_family ON identity_refresh_token (family_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_identity_refresh_token_user ON identity_refresh_token (user_id);
