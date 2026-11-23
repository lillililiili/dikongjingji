-- S5-8 手机号登录与更换、登录设备、注销、个人信息导出（docs/redesign/08-后续开发计划.md §2 S5-8、§5“账号”默认决定；
-- 契约 contracts/openapi-identity-account.yaml 1.0.0）。只加不改已有数据。

-- 手机号：中国大陆 11 位手机号，一个手机号只能绑定一个账号（注销匿名化时清空，号码可以重新注册）。
-- 与收货地址的手机号一样按敏感资料处理：只给本人看完整号码，其余地方一律打码（138****2026）。
-- 已有账号（账号密码登录的开发和机构账号）可以没有手机号。
ALTER TABLE identity_user
    ADD COLUMN mobile varchar(11) CHECK (mobile ~ '^1[3-9][0-9]{9}$'),
    ADD COLUMN registered_via varchar(16) NOT NULL DEFAULT 'PASSWORD' CHECK (registered_via IN ('PASSWORD', 'PHONE')),
    ADD COLUMN closed_at timestamptz;

CREATE UNIQUE INDEX uq_identity_user_mobile
    ON identity_user (mobile)
    WHERE mobile IS NOT NULL;

-- 注销完成（匿名化）的账号状态是 CLOSED：不能再登录，令牌一律失效；订单等业务数据保留但不再能对应到人。
ALTER TABLE identity_user DROP CONSTRAINT identity_user_status_check;
ALTER TABLE identity_user ADD CONSTRAINT identity_user_status_check
    CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED', 'CLOSED'));

-- 短信验证码：只存摘要。purpose：LOGIN 登录或注册、VERIFY_CURRENT 验证当前手机号（换号第一步）、BIND_NEW 绑定新号、
-- EXPORT 申请导出个人信息。5 分钟有效，错 5 次作废，用过即作废。CHANGE_TICKET 是换号第一步通过后发的一次性凭据（10 分钟）。
CREATE TABLE identity_sms_code (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    phone varchar(11) NOT NULL CHECK (phone ~ '^1[3-9][0-9]{9}$'),
    purpose varchar(16) NOT NULL CHECK (purpose IN ('LOGIN', 'VERIFY_CURRENT', 'BIND_NEW', 'EXPORT', 'CHANGE_TICKET')),
    user_id uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    code_hash char(64) NOT NULL,
    failed_attempts integer NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    sent_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    CONSTRAINT ck_identity_sms_code_expiry CHECK (expires_at > sent_at)
);

CREATE INDEX idx_identity_sms_code_phone_time
    ON identity_sms_code (phone, purpose, sent_at DESC);

-- 每个手机号的验证错误计数：1 小时内错 10 次锁 30 分钟（这期间不发也不认验证码）。
CREATE TABLE identity_phone_guard (
    phone varchar(11) PRIMARY KEY CHECK (phone ~ '^1[3-9][0-9]{9}$'),
    failed_count integer NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    window_started_at timestamptz NOT NULL,
    locked_until timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 本机模拟短信（开发环境 identity.sms.provider = fake）发出的内容，开发和 E2E 从这里读验证码；其他环境这张表不写。
CREATE TABLE identity_dev_sms_outbox (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    phone varchar(11) NOT NULL,
    content varchar(200) NOT NULL,
    sent_at timestamptz NOT NULL
);

CREATE INDEX idx_identity_dev_sms_outbox_phone
    ON identity_dev_sms_outbox (phone, sent_at DESC);

-- 登录会话（登录设备）：每次登录一行，访问令牌里带会话 ID（claim sid）。同一台设备（device_key，App 首次启动生成的随机 ID）
-- 再登录时上一行标记为 REPLACED；退出某台设备即撤销那一行，带它的令牌立刻失效。
-- 只记设备名称、平台和城市（城市编码，只到地级市），不记 IP 和精确位置。刷新令牌上线后按会话续期（合并时统一）。
CREATE TABLE identity_login_session (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    device_key varchar(64),
    device_name varchar(40) NOT NULL,
    platform varchar(16) NOT NULL CHECK (platform IN ('ANDROID', 'IOS', 'H5', 'MP_WEIXIN', 'OTHER')),
    city_code varchar(16),
    login_method varchar(16) NOT NULL CHECK (login_method IN ('PASSWORD', 'SMS_CODE')),
    signed_in_at timestamptz NOT NULL,
    last_active_at timestamptz NOT NULL,
    revoked_at timestamptz,
    revoke_reason varchar(16) CHECK (revoke_reason IN ('SIGNED_OUT', 'REMOTE', 'REPLACED', 'PHONE_CHANGED', 'CLOSED')),
    CONSTRAINT ck_identity_login_session_revoked CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL))
);

CREATE INDEX idx_identity_login_session_user_active
    ON identity_login_session (user_id, last_active_at DESC)
    WHERE revoked_at IS NULL;

-- 注销申请：提交后 15 天冷静期，期间可撤回（不能下单），到期由服务端匿名化。一个账号同时只有一条处理中的申请。
CREATE TABLE identity_closure_request (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING', 'WITHDRAWN', 'COMPLETED')),
    requested_at timestamptz NOT NULL,
    due_at timestamptz NOT NULL,
    withdrawn_at timestamptz,
    completed_at timestamptz,
    CONSTRAINT ck_identity_closure_due CHECK (due_at > requested_at),
    CONSTRAINT ck_identity_closure_withdrawn CHECK ((status = 'WITHDRAWN') = (withdrawn_at IS NOT NULL)),
    CONSTRAINT ck_identity_closure_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_identity_closure_pending
    ON identity_closure_request (user_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_identity_closure_due
    ON identity_closure_request (due_at)
    WHERE status = 'PENDING';

-- 个人信息导出：申请后由服务端异步生成 ZIP（每项一个 JSON），文件放受控存储（开发环境本机目录），只存元数据。
-- 下载只经一次性的短时效下载凭据（摘要存在 download_token_hash，5 分钟有效）；副本 7 天后过期删除文件。
CREATE TABLE identity_data_export (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    sections varchar(200) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING', 'READY', 'FAILED', 'EXPIRED')),
    requested_at timestamptz NOT NULL,
    ready_at timestamptz,
    expires_at timestamptz,
    storage_key varchar(200) UNIQUE,
    size_bytes bigint CHECK (size_bytes IS NULL OR size_bytes > 0),
    sha256 char(64),
    download_token_hash char(64),
    download_token_expires_at timestamptz,
    download_count integer NOT NULL DEFAULT 0 CHECK (download_count >= 0),
    CONSTRAINT ck_identity_data_export_ready
        CHECK (status <> 'READY' OR (ready_at IS NOT NULL AND storage_key IS NOT NULL))
);

CREATE INDEX idx_identity_data_export_user
    ON identity_data_export (user_id, requested_at DESC);

CREATE INDEX idx_identity_data_export_pending
    ON identity_data_export (requested_at)
    WHERE status = 'PENDING';
