-- S3-3：App 内“工作模式”核销工作台（docs/redesign/08-后续开发计划.md §5 D8，契约 contracts/openapi-checkin-workbench.yaml）。
-- 操作员在工作台上扫码或输码核销活动凭证，每一次都记一行：核销成功的，和被拦下的（重复、过期、非本机构、
-- 场次不符、已取消、未付款）。被拦下的那一行之后由同一个操作员登记处理（放行、不予核销、拦截）和备注，只能登记一次。
-- 记录只给操作员本人看，不对客户展示；凭证号只存尾 4 位，持有人姓名不另存，读的时候从凭证取并打码。

CREATE TABLE activity_checkin_record (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- 操作员和他所属的机构（取自令牌）。
    organization_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    operator_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    ticket_id uuid NOT NULL REFERENCES activity_enrollment_ticket(id) ON DELETE RESTRICT,
    -- 当时凭证所属的场次；非本机构的凭证不记（不透露别的机构的活动）。
    ticket_session_id uuid REFERENCES activity_session(id) ON DELETE RESTRICT,
    code_tail char(4) NOT NULL,
    method varchar(16) NOT NULL CHECK (method IN ('SCAN', 'MANUAL')),
    -- 工作台上正在核销的场次。可能是场馆的场次（同一个机构既办活动又运营场馆），所以不加外键。
    current_session_kind varchar(16) CHECK (current_session_kind IN ('ACTIVITY', 'VENUE')),
    current_session_id uuid,
    outcome varchar(24) NOT NULL CHECK (outcome IN (
        'CHECKED_IN', 'REPEATED', 'EXPIRED', 'OTHER_ORGANIZATION', 'SESSION_MISMATCH', 'CANCELLED', 'UNPAID')),
    people smallint NOT NULL DEFAULT 1 CHECK (people = 1),
    -- 这张凭证付的钱（分），免费为 0；非本机构的不记。
    amount_minor bigint CHECK (amount_minor >= 0),
    -- 重复核销时，这张凭证第一次核销的时间。
    first_checked_in_at timestamptz,
    -- 人工处理：RELEASE 登记放行（改到当前场次并核销）、REJECT 不予核销、BLOCK 拦截。
    resolution varchar(16) CHECK (resolution IN ('RELEASE', 'REJECT', 'BLOCK')),
    identity_checked boolean NOT NULL DEFAULT false,
    resolution_note varchar(200),
    resolved_at timestamptz,
    -- 登记放行后凭证所在的场次。
    admitted_session_id uuid REFERENCES activity_session(id) ON DELETE RESTRICT,
    occurred_at timestamptz NOT NULL,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- 每一项都写明 IS NOT NULL / IS DISTINCT FROM：CHECK 遇到 NULL 的比较会算通过。
    CONSTRAINT ck_activity_checkin_current CHECK ((current_session_kind IS NULL) = (current_session_id IS NULL)),
    CONSTRAINT ck_activity_checkin_ticket_session
        CHECK ((outcome = 'OTHER_ORGANIZATION') = (ticket_session_id IS NULL)),
    CONSTRAINT ck_activity_checkin_amount CHECK ((outcome = 'OTHER_ORGANIZATION') = (amount_minor IS NULL)),
    CONSTRAINT ck_activity_checkin_repeated CHECK ((outcome = 'REPEATED') = (first_checked_in_at IS NOT NULL)),
    CONSTRAINT ck_activity_checkin_resolved CHECK ((resolution IS NULL) = (resolved_at IS NULL)),
    CONSTRAINT ck_activity_checkin_success CHECK (outcome <> 'CHECKED_IN' OR resolution IS NULL),
    CONSTRAINT ck_activity_checkin_note CHECK (resolution IS NOT NULL OR resolution_note IS NULL),
    CONSTRAINT ck_activity_checkin_admitted
        CHECK ((resolution IS NOT DISTINCT FROM 'RELEASE') = (admitted_session_id IS NOT NULL)),
    CONSTRAINT ck_activity_checkin_identity CHECK (resolution IS DISTINCT FROM 'RELEASE' OR identity_checked)
);

CREATE INDEX idx_activity_checkin_record_operator
    ON activity_checkin_record (operator_id, organization_id, occurred_at DESC, id);

CREATE INDEX idx_activity_checkin_record_ticket
    ON activity_checkin_record (ticket_id, occurred_at DESC);
