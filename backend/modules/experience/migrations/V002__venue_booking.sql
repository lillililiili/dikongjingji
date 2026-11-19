-- E10：到馆预约（docs/redesign/03-App实现任务计划.md §21）。预约单、预约时的参与人快照、状态变化记录。
-- identity_user 由身份模块创建，本模块排在其后（ModuleFlywayConfiguration）。
-- 参与人名单属于活动模块（activity_participant），交易订单和退款属于交易模块：都只存 ID，不加外键。
-- 参与人的姓名、年龄是敏感资料：读写都以 account_id 为条件；核销以场馆运营机构为条件。

CREATE TABLE venue_booking (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    venue_id uuid NOT NULL REFERENCES venue(id) ON DELETE RESTRICT,
    project_id uuid NOT NULL REFERENCES venue_project(id) ON DELETE RESTRICT,
    -- 改期后是新的场次；同一项目里换，不换价。
    session_id uuid NOT NULL REFERENCES venue_session(id) ON DELETE RESTRICT,
    -- PENDING_PAYMENT 待付款；CONFIRMED 待到馆（已付款或免费）；CHECKED_IN 已核销；
    -- CANCELLED 付过款（或免费）之后取消；CLOSED 没付款就关闭了（超时或取消）。
    -- “已过期”（场次结束还没核销）按时间算，不落库。
    status varchar(16) NOT NULL
        CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CHECKED_IN', 'CANCELLED', 'CLOSED')),
    -- 核销码，一单一个（几个人共用），付款成功（免费的提交即）生成；改期换新码，旧码作废。随机，不含个人信息。
    code varchar(24) UNIQUE CHECK (code ~ '^VEN-[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}$'),
    people smallint NOT NULL CHECK (people BETWEEN 1 AND 10),
    unit_amount_minor bigint NOT NULL CHECK (unit_amount_minor >= 0),
    amount_minor bigint NOT NULL,
    -- 交易订单（免费预约没有）和付款截止时间（15 分钟，不晚于场次开始）。
    trade_order_id uuid UNIQUE,
    pay_before timestamptz,
    -- 取消已付款的预约时整单退款（交易模块的退款单 ID）。
    refund_id uuid,
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    notice_agreed_at timestamptz NOT NULL,
    confirmed_at timestamptz,
    checked_in_at timestamptz,
    checked_in_by uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    cancelled_at timestamptz,
    closed_at timestamptz,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, idempotency_key),
    CONSTRAINT ck_venue_booking_amount CHECK (amount_minor = unit_amount_minor * people),
    -- 要付款的（金额大于 0）一定有交易订单和付款截止时间，免费的一定没有。
    CONSTRAINT ck_venue_booking_payment CHECK (
        (amount_minor > 0 AND trade_order_id IS NOT NULL AND pay_before IS NOT NULL)
        OR (amount_minor = 0 AND trade_order_id IS NULL AND pay_before IS NULL)
    ),
    -- 待付款、没付款就关闭的没有核销码；其余的都有（取消后保留作废的码）。
    CONSTRAINT ck_venue_booking_code CHECK ((status IN ('PENDING_PAYMENT', 'CLOSED')) = (code IS NULL)),
    CONSTRAINT ck_venue_booking_confirmed CHECK ((status IN ('PENDING_PAYMENT', 'CLOSED')) = (confirmed_at IS NULL)),
    CONSTRAINT ck_venue_booking_pending CHECK (status NOT IN ('PENDING_PAYMENT', 'CLOSED') OR trade_order_id IS NOT NULL),
    CONSTRAINT ck_venue_booking_checked_in CHECK ((status = 'CHECKED_IN') = (checked_in_at IS NOT NULL)),
    CONSTRAINT ck_venue_booking_checked_in_by CHECK ((status = 'CHECKED_IN') = (checked_in_by IS NOT NULL)),
    CONSTRAINT ck_venue_booking_cancelled CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    CONSTRAINT ck_venue_booking_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    CONSTRAINT ck_venue_booking_refund CHECK (
        refund_id IS NULL OR (status = 'CANCELLED' AND trade_order_id IS NOT NULL)
    )
);

CREATE INDEX idx_venue_booking_account ON venue_booking (account_id, created_at DESC, id);
CREATE INDEX idx_venue_booking_session ON venue_booking (session_id, status);

-- 预约时的参与人快照：之后改名或删除参与人，这张预约不变。session_id 跟着预约走（改期时一起改），
-- active 在预约关闭、取消时变成 false：同一个人同一场次只能有一张还有效（待付款也算）的预约。
CREATE TABLE venue_booking_participant (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id uuid NOT NULL REFERENCES venue_booking(id) ON DELETE RESTRICT,
    session_id uuid NOT NULL REFERENCES venue_session(id) ON DELETE RESTRICT,
    participant_id uuid NOT NULL,
    participant_name varchar(40) NOT NULL,
    participant_relation varchar(16) NOT NULL CHECK (participant_relation IN ('SELF', 'CHILD')),
    -- 场次当天的周岁；“本人”不知道出生年月，为空。
    participant_age smallint CHECK (participant_age >= 0),
    active boolean NOT NULL DEFAULT true,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (booking_id, participant_id)
);

CREATE UNIQUE INDEX uq_venue_booking_participant_active
    ON venue_booking_participant (session_id, participant_id)
    WHERE active;

CREATE INDEX idx_venue_booking_participant_booking ON venue_booking_participant (booking_id, created_at, id);

-- 状态变化记录，只追加：谁、什么时候、做了什么；改期记下从哪一场到哪一场。
CREATE TABLE venue_booking_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    booking_id uuid NOT NULL REFERENCES venue_booking(id) ON DELETE RESTRICT,
    event_type varchar(24) NOT NULL
        CHECK (event_type IN ('BOOKED', 'PAYMENT_CONFIRMED', 'PAYMENT_CLOSED', 'PAYMENT_RESTORED',
                              'RESCHEDULED', 'CANCELLED', 'REFUND_REQUESTED', 'CHECKED_IN')),
    -- 付款到账、关闭、恢复由渠道结果或超时触发，没有操作人；其余的必须记是谁。
    actor_id uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    from_session_id uuid REFERENCES venue_session(id) ON DELETE RESTRICT,
    to_session_id uuid REFERENCES venue_session(id) ON DELETE RESTRICT,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_venue_booking_event_actor CHECK (
        actor_id IS NOT NULL OR event_type IN ('PAYMENT_CONFIRMED', 'PAYMENT_CLOSED', 'PAYMENT_RESTORED')
    ),
    CONSTRAINT ck_venue_booking_event_sessions CHECK (
        (event_type = 'RESCHEDULED') = (from_session_id IS NOT NULL AND to_session_id IS NOT NULL)
    )
);

CREATE INDEX idx_venue_booking_event_booking ON venue_booking_event (booking_id, occurred_at, id);
