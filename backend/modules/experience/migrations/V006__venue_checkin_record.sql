-- S3-3：App 内“工作模式”核销工作台（docs/redesign/08-后续开发计划.md §5 D8，契约 contracts/openapi-checkin-workbench.yaml）。
-- 场馆操作员在工作台上扫码或输码核销到馆预约（一单一码），每一次都记一行：核销成功的，和被拦下的（重复、过期、
-- 非本机构、场次不符、已取消）。到馆预约在付款前没有核销码，所以没有“未付款”。被拦下的那一行之后由同一个操作员
-- 登记处理（放行、不予核销、拦截）和备注，只能登记一次。记录只给操作员本人看；核销码只存尾 4 位，持有人姓名不另存。

CREATE TABLE venue_checkin_record (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    operator_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    booking_id uuid NOT NULL REFERENCES venue_booking(id) ON DELETE RESTRICT,
    -- 当时预约所在的场次；非本机构的不记（不透露别的机构的场馆）。
    booking_session_id uuid REFERENCES venue_session(id) ON DELETE RESTRICT,
    code_tail char(4) NOT NULL,
    method varchar(16) NOT NULL CHECK (method IN ('SCAN', 'MANUAL')),
    -- 工作台上正在核销的场次。可能是活动的场次（同一个机构既办活动又运营场馆），所以不加外键。
    current_session_kind varchar(16) CHECK (current_session_kind IN ('ACTIVITY', 'VENUE')),
    current_session_id uuid,
    outcome varchar(24) NOT NULL CHECK (outcome IN (
        'CHECKED_IN', 'REPEATED', 'EXPIRED', 'OTHER_ORGANIZATION', 'SESSION_MISMATCH', 'CANCELLED')),
    -- 这张预约的人数；非本机构的记 1（不透露别的机构的预约）。
    people smallint NOT NULL CHECK (people BETWEEN 1 AND 10),
    -- 这张预约付的钱（分），免费为 0；非本机构的不记。
    amount_minor bigint CHECK (amount_minor >= 0),
    first_checked_in_at timestamptz,
    resolution varchar(16) CHECK (resolution IN ('RELEASE', 'REJECT', 'BLOCK')),
    identity_checked boolean NOT NULL DEFAULT false,
    resolution_note varchar(200),
    resolved_at timestamptz,
    -- 登记放行后预约所在的场次。
    admitted_session_id uuid REFERENCES venue_session(id) ON DELETE RESTRICT,
    occurred_at timestamptz NOT NULL,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- 每一项都写明 IS NOT NULL / IS DISTINCT FROM：CHECK 遇到 NULL 的比较会算通过。
    CONSTRAINT ck_venue_checkin_current CHECK ((current_session_kind IS NULL) = (current_session_id IS NULL)),
    CONSTRAINT ck_venue_checkin_booking_session
        CHECK ((outcome = 'OTHER_ORGANIZATION') = (booking_session_id IS NULL)),
    CONSTRAINT ck_venue_checkin_amount CHECK ((outcome = 'OTHER_ORGANIZATION') = (amount_minor IS NULL)),
    CONSTRAINT ck_venue_checkin_repeated CHECK ((outcome = 'REPEATED') = (first_checked_in_at IS NOT NULL)),
    CONSTRAINT ck_venue_checkin_resolved CHECK ((resolution IS NULL) = (resolved_at IS NULL)),
    CONSTRAINT ck_venue_checkin_success CHECK (outcome <> 'CHECKED_IN' OR resolution IS NULL),
    CONSTRAINT ck_venue_checkin_note CHECK (resolution IS NOT NULL OR resolution_note IS NULL),
    CONSTRAINT ck_venue_checkin_admitted
        CHECK ((resolution IS NOT DISTINCT FROM 'RELEASE') = (admitted_session_id IS NOT NULL)),
    CONSTRAINT ck_venue_checkin_identity CHECK (resolution IS DISTINCT FROM 'RELEASE' OR identity_checked)
);

CREATE INDEX idx_venue_checkin_record_operator
    ON venue_checkin_record (operator_id, organization_id, occurred_at DESC, id);

CREATE INDEX idx_venue_checkin_record_booking
    ON venue_checkin_record (booking_id, occurred_at DESC);
