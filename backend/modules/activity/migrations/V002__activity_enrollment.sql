-- 免费活动报名：参与人、报名单、凭证（一人一码）、状态变化记录。
-- identity_user 由身份模块的迁移创建，本模块排在其后（ModuleFlywayConfiguration）。
-- 参与人的姓名、出生年月属于敏感资料：所有读写都以 owner_id / account_id 为条件，不存证件号码和手机号。

CREATE TABLE activity_participant (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    -- SELF：账号持有人本人，按成年人处理；CHILD：由监护人账号维护的未成年子女。
    relation varchar(16) NOT NULL CHECK (relation IN ('SELF', 'CHILD')),
    name varchar(40) NOT NULL CHECK (btrim(name) <> ''),
    -- 只收到“年月”，存当月 1 日。
    birth_month date CHECK (birth_month IS NULL OR extract(day FROM birth_month) = 1),
    -- 添加子女时监护人勾选确认的时间。
    guardian_confirmed_at timestamptz,
    -- “活动照片可公开使用”的单独授权，可随时撤回；changed_at 记最后一次改动。
    photo_use_authorized boolean NOT NULL DEFAULT false,
    photo_use_changed_at timestamptz,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DELETED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((relation = 'CHILD') = (birth_month IS NOT NULL)),
    CHECK (relation <> 'CHILD' OR guardian_confirmed_at IS NOT NULL),
    CHECK (relation = 'CHILD' OR photo_use_authorized = false)
);

-- 一个账号只有一个“本人”。
CREATE UNIQUE INDEX uq_activity_participant_self
    ON activity_participant (owner_id)
    WHERE relation = 'SELF' AND status = 'ACTIVE';

-- 同名同出生年月的子女不能重复添加。
CREATE UNIQUE INDEX uq_activity_participant_child
    ON activity_participant (owner_id, name, birth_month)
    WHERE relation = 'CHILD' AND status = 'ACTIVE';

CREATE INDEX idx_activity_participant_owner
    ON activity_participant (owner_id, created_at, id)
    WHERE status = 'ACTIVE';

CREATE TABLE activity_enrollment (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    activity_id uuid NOT NULL REFERENCES activity(id) ON DELETE RESTRICT,
    session_id uuid NOT NULL REFERENCES activity_session(id) ON DELETE RESTRICT,
    -- 客户端每次提交带的幂等键；request_hash 用来识别同一个键被用于不同请求。
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    -- CANCELLED：名下凭证全部取消。是否“已结束”由场次时间算，不落库。
    status varchar(16) NOT NULL DEFAULT 'CONFIRMED' CHECK (status IN ('CONFIRMED', 'CANCELLED')),
    -- 提交时勾选“已阅读并同意报名须知”的时间。
    notice_agreed_at timestamptz NOT NULL,
    cancelled_at timestamptz,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, idempotency_key),
    CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

CREATE INDEX idx_activity_enrollment_account
    ON activity_enrollment (account_id, status, session_id);

CREATE TABLE activity_enrollment_ticket (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    enrollment_id uuid NOT NULL REFERENCES activity_enrollment(id) ON DELETE RESTRICT,
    session_id uuid NOT NULL REFERENCES activity_session(id) ON DELETE RESTRICT,
    participant_id uuid NOT NULL REFERENCES activity_participant(id) ON DELETE RESTRICT,
    -- 报名时的参与人快照：之后改名或删除参与人，凭证不变。
    participant_name varchar(40) NOT NULL,
    participant_relation varchar(16) NOT NULL CHECK (participant_relation IN ('SELF', 'CHILD')),
    participant_age smallint CHECK (participant_age >= 0),
    -- 凭证号，也是二维码的内容。随机生成，不含任何个人信息。
    ticket_code varchar(24) NOT NULL UNIQUE,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CHECKED_IN', 'CANCELLED')),
    checked_in_at timestamptz,
    checked_in_by uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    cancelled_at timestamptz,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status = 'CHECKED_IN') = (checked_in_at IS NOT NULL)),
    CHECK ((status = 'CHECKED_IN') = (checked_in_by IS NOT NULL)),
    CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL))
);

-- 同一个参与人在同一场次只能有一张有效凭证；取消后可以重新报名。
CREATE UNIQUE INDEX uq_activity_ticket_session_participant
    ON activity_enrollment_ticket (session_id, participant_id)
    WHERE status <> 'CANCELLED';

CREATE INDEX idx_activity_ticket_enrollment
    ON activity_enrollment_ticket (enrollment_id, created_at, id);

-- 状态变化记录，只追加：谁在什么时候报了名、取消了哪张凭证、核销了哪张凭证。
CREATE TABLE activity_enrollment_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    enrollment_id uuid NOT NULL REFERENCES activity_enrollment(id) ON DELETE RESTRICT,
    ticket_id uuid REFERENCES activity_enrollment_ticket(id) ON DELETE RESTRICT,
    event_type varchar(32) NOT NULL
        CHECK (event_type IN ('ENROLLED', 'TICKET_CANCELLED', 'ENROLLMENT_CANCELLED', 'TICKET_CHECKED_IN')),
    actor_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_activity_enrollment_event_enrollment
    ON activity_enrollment_event (enrollment_id, occurred_at, id);
