-- S5-3：候补、团队报名、改期、退改记录和结果记录（docs/redesign/08-后续开发计划.md §5“候补、团队报名、改期”，
-- 设计见 docs/redesign/S5-3-活动候补团队改期与结果.md，契约 contracts/openapi-activity.yaml 1.4.0）。
-- 已有的报名单、凭证、参与人、状态记录都满足下面每一条新约束。

-- ---- 参与人：团队队员 ----

-- MEMBER：领队在团队报名时现填姓名和出生年月的队员，记在领队账号下。不出现在“参与人”页和个人报名的名单里，
-- 不能单独授权照片使用；和子女一样只收出生年月。添加时领队勾选“已取得全部队员监护人的同意”，记在 guardian_confirmed_at。
ALTER TABLE activity_participant DROP CONSTRAINT activity_participant_relation_check;
ALTER TABLE activity_participant ADD CONSTRAINT activity_participant_relation_check
    CHECK (relation IN ('SELF', 'CHILD', 'MEMBER'));
-- V002 的三条表级约束是匿名的，Postgres 按列名起名；换成按名字的新约束。
ALTER TABLE activity_participant DROP CONSTRAINT IF EXISTS activity_participant_check;
ALTER TABLE activity_participant DROP CONSTRAINT IF EXISTS activity_participant_check1;
ALTER TABLE activity_participant DROP CONSTRAINT IF EXISTS activity_participant_check2;
ALTER TABLE activity_participant ADD CONSTRAINT ck_activity_participant_birth_month
    CHECK ((relation IN ('CHILD', 'MEMBER')) = (birth_month IS NOT NULL));
ALTER TABLE activity_participant ADD CONSTRAINT ck_activity_participant_guardian
    CHECK (relation = 'SELF' OR guardian_confirmed_at IS NOT NULL);
ALTER TABLE activity_participant ADD CONSTRAINT ck_activity_participant_photo_use
    CHECK (relation = 'CHILD' OR photo_use_authorized = false);

-- 同名同出生年月的队员只记一个，下次团队报名直接用。
CREATE UNIQUE INDEX uq_activity_participant_member
    ON activity_participant (owner_id, name, birth_month)
    WHERE relation = 'MEMBER' AND status = 'ACTIVE';

-- ---- 凭证 ----

-- 队员的凭证快照关系是 MEMBER。
ALTER TABLE activity_enrollment_ticket DROP CONSTRAINT activity_enrollment_ticket_participant_relation_check;
ALTER TABLE activity_enrollment_ticket ADD CONSTRAINT activity_enrollment_ticket_participant_relation_check
    CHECK (participant_relation IN ('SELF', 'CHILD', 'MEMBER'));

-- 取消原因加 RESCHEDULED：改期后原凭证作废（换了新凭证，名额转到新场次，不退款）。
ALTER TABLE activity_enrollment_ticket DROP CONSTRAINT activity_enrollment_ticket_cancel_cause_check;
ALTER TABLE activity_enrollment_ticket ADD CONSTRAINT activity_enrollment_ticket_cancel_cause_check
    CHECK (cancel_cause IN ('BUYER', 'PAYMENT_CLOSED', 'RESCHEDULED'));

-- ---- 报名单：团队、改期 ----

-- TEAM：领队代报 2–20 人的团队报名单，团队名称和领队资料在 activity_enrollment_team。
ALTER TABLE activity_enrollment ADD COLUMN kind varchar(16) NOT NULL DEFAULT 'INDIVIDUAL'
    CHECK (kind IN ('INDIVIDUAL', 'TEAM'));
-- 最近一次改期前的场次和改期时间（订单页“原时间 / 新时间”）。没改过期时都为空。
ALTER TABLE activity_enrollment ADD COLUMN previous_session_id uuid REFERENCES activity_session(id) ON DELETE RESTRICT;
ALTER TABLE activity_enrollment ADD COLUMN rescheduled_at timestamptz;
ALTER TABLE activity_enrollment ADD CONSTRAINT ck_activity_enrollment_rescheduled
    CHECK ((previous_session_id IS NULL) = (rescheduled_at IS NULL));

-- 团队资料。领队手机只给领队本人（页面打码）和主办机构看，主办方有事联系领队；队员不收手机号。
CREATE TABLE activity_enrollment_team (
    enrollment_id uuid PRIMARY KEY REFERENCES activity_enrollment(id) ON DELETE RESTRICT,
    team_name varchar(40) NOT NULL CHECK (btrim(team_name) <> ''),
    leader_name varchar(20) NOT NULL CHECK (btrim(leader_name) <> ''),
    leader_phone varchar(11) NOT NULL CHECK (leader_phone ~ '^1[0-9]{10}$'),
    -- 提交时勾选“已取得全部队员监护人的同意”的时间。
    guardian_consent_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- ---- 退改记录 ----

-- 每次取消（退款）或改期记一行，退改详情页按它显示。凭证上记着是哪一次把它取消的、哪一次把它发出来的。
-- reason 是页面选的申请原因（可以不选）：TIME_CONFLICT 时间冲突、UNWELL 身体不适、PLAN_CHANGED 行程有变、OTHER 其他。
CREATE TABLE activity_enrollment_change (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    enrollment_id uuid NOT NULL REFERENCES activity_enrollment(id) ON DELETE RESTRICT,
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    kind varchar(16) NOT NULL CHECK (kind IN ('CANCEL', 'RESCHEDULE')),
    reason varchar(16) CHECK (reason IN ('TIME_CONFLICT', 'UNWELL', 'PLAN_CHANGED', 'OTHER')),
    people smallint NOT NULL CHECK (people > 0),
    from_session_id uuid NOT NULL REFERENCES activity_session(id) ON DELETE RESTRICT,
    to_session_id uuid REFERENCES activity_session(id) ON DELETE RESTRICT,
    -- 收费报名取消的那一笔退款（交易模块的退款单，不加外键）和金额（分）；免费、改期为空。
    refund_id uuid,
    refund_amount_minor bigint CHECK (refund_amount_minor > 0),
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_activity_change_target CHECK ((kind = 'RESCHEDULE') = (to_session_id IS NOT NULL)),
    CONSTRAINT ck_activity_change_refund CHECK ((refund_id IS NULL) = (refund_amount_minor IS NULL)),
    CONSTRAINT ck_activity_change_refund_kind CHECK (refund_id IS NULL OR kind = 'CANCEL')
);

CREATE INDEX idx_activity_enrollment_change_enrollment
    ON activity_enrollment_change (enrollment_id, created_at DESC, id);

ALTER TABLE activity_enrollment_ticket ADD COLUMN cancelled_by_change_id uuid
    REFERENCES activity_enrollment_change(id) ON DELETE RESTRICT;
ALTER TABLE activity_enrollment_ticket ADD COLUMN issued_by_change_id uuid
    REFERENCES activity_enrollment_change(id) ON DELETE RESTRICT;
ALTER TABLE activity_enrollment_ticket ADD CONSTRAINT ck_activity_ticket_cancel_change
    CHECK (cancelled_by_change_id IS NULL OR status = 'CANCELLED');
ALTER TABLE activity_enrollment_ticket ADD CONSTRAINT ck_activity_ticket_rescheduled
    CHECK (cancel_cause IS DISTINCT FROM 'RESCHEDULED' OR cancelled_by_change_id IS NOT NULL);

-- 状态变化记录加改期、候补转正生成报名单两类。
ALTER TABLE activity_enrollment_event DROP CONSTRAINT activity_enrollment_event_event_type_check;
ALTER TABLE activity_enrollment_event ADD CONSTRAINT activity_enrollment_event_event_type_check
    CHECK (event_type IN ('ENROLLED', 'TICKET_CANCELLED', 'ENROLLMENT_CANCELLED', 'TICKET_CHECKED_IN',
                          'PAYMENT_CONFIRMED', 'PAYMENT_CLOSED', 'PAYMENT_RESTORED', 'REFUND_REQUESTED',
                          'RESCHEDULED', 'ENROLLED_FROM_WAITLIST', 'RESULT_RECORDED'));

-- ---- 候补 ----

-- 满员且允许候补的场次可以排队。名额释放时按加入先后（seq）转正：从排在最前的开始，人数放得下的依次转正
-- （同单几个人一起转正，余位不够这一单时它继续排队，后面放得下的先转正）。
-- 转正（OFFERED）时名额就为它占住（计入场次的 enrolled_count），保留 15 分钟、且不晚于场次开始：
-- 免费的在这段时间里“确认报名”，收费的“去付款”（这时建报名单和交易订单，付款截止就是这个时间）。
-- 超时没确认（EXPIRED）、放弃（ABANDONED）都把占住的名额放回，再给下一位。场次开始时还在排队的候补结束（ENDED）。
-- ENROLLED：已经按这次转正建了报名单（enrollment_id）；之后付款、关闭、取消看报名单
-- （收费的到了付款截止还没付，报名单关闭，候补读成“已超时”）。
CREATE TABLE activity_waitlist (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    seq bigserial NOT NULL UNIQUE,
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    activity_id uuid NOT NULL REFERENCES activity(id) ON DELETE RESTRICT,
    session_id uuid NOT NULL REFERENCES activity_session(id) ON DELETE RESTRICT,
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    people smallint NOT NULL CHECK (people BETWEEN 1 AND 10),
    status varchar(16) NOT NULL DEFAULT 'WAITING'
        CHECK (status IN ('WAITING', 'OFFERED', 'ENROLLED', 'EXPIRED', 'ENDED', 'ABANDONED')),
    -- 加入时看到的单价（分），只用于页面显示“候补成功后应付”；转正付款按那时的单价。免费为空。
    unit_amount_minor bigint CHECK (unit_amount_minor > 0),
    notice_agreed_at timestamptz NOT NULL,
    offered_at timestamptz,
    offer_expires_at timestamptz,
    enrollment_id uuid UNIQUE REFERENCES activity_enrollment(id) ON DELETE RESTRICT,
    ended_at timestamptz,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    UNIQUE (account_id, idempotency_key),
    CONSTRAINT ck_activity_waitlist_offer CHECK ((offered_at IS NULL) = (offer_expires_at IS NULL)),
    CONSTRAINT ck_activity_waitlist_offered CHECK (status NOT IN ('OFFERED', 'ENROLLED', 'EXPIRED') OR offered_at IS NOT NULL),
    -- 建了报名单之后放弃（待付款时取消）的，状态是 ABANDONED，报名单 ID 仍留着。
    CONSTRAINT ck_activity_waitlist_enrolled CHECK (status <> 'ENROLLED' OR enrollment_id IS NOT NULL),
    CONSTRAINT ck_activity_waitlist_enrollment_status
        CHECK (enrollment_id IS NULL OR status IN ('ENROLLED', 'ABANDONED')),
    CONSTRAINT ck_activity_waitlist_ended
        CHECK ((status IN ('EXPIRED', 'ENDED', 'ABANDONED')) = (ended_at IS NOT NULL))
);

-- 转正时按场次找排队的；超时任务找过了保留时间的。
CREATE INDEX idx_activity_waitlist_queue ON activity_waitlist (session_id, seq) WHERE status = 'WAITING';
CREATE INDEX idx_activity_waitlist_offer ON activity_waitlist (offer_expires_at) WHERE status = 'OFFERED';
CREATE INDEX idx_activity_waitlist_account ON activity_waitlist (account_id, created_at DESC, id);

-- 一起候补的参与人（加入时的快照；年龄按场次当天算）。同一个人同一场次只能在一条还在排队或已转正的候补里，
-- 由服务在锁住场次之后检查。
CREATE TABLE activity_waitlist_member (
    waitlist_id uuid NOT NULL REFERENCES activity_waitlist(id) ON DELETE RESTRICT,
    participant_id uuid NOT NULL REFERENCES activity_participant(id) ON DELETE RESTRICT,
    participant_name varchar(40) NOT NULL,
    participant_relation varchar(16) NOT NULL CHECK (participant_relation IN ('SELF', 'CHILD')),
    participant_age smallint CHECK (participant_age >= 0),
    PRIMARY KEY (waitlist_id, participant_id)
);

CREATE INDEX idx_activity_waitlist_member_participant ON activity_waitlist_member (participant_id);

-- ---- 结果记录 ----

-- 主办或承办机构在 Web 工作台录入签到之后的完成情况、成绩和证书说明，一张凭证一条；可以改，改的时候带版本号。
-- 客户在凭证、订单页看到。只能给已签到的凭证录入。
CREATE TABLE activity_ticket_result (
    ticket_id uuid PRIMARY KEY REFERENCES activity_enrollment_ticket(id) ON DELETE RESTRICT,
    organization_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    -- COMPLETED 完成 / INCOMPLETE 未完成
    completion varchar(16) NOT NULL CHECK (completion IN ('COMPLETED', 'INCOMPLETE')),
    -- 成绩，比如“完成任务 5/5 · 用时 18′42″”“二等奖”。
    score_text varchar(40) CHECK (score_text IS NULL OR btrim(score_text) <> ''),
    -- 证书说明，比如“完赛证书由主办方现场发放”。只是文字，不生成文件。
    certificate_note varchar(100) CHECK (certificate_note IS NULL OR btrim(certificate_note) <> ''),
    recorded_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    recorded_at timestamptz NOT NULL,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
