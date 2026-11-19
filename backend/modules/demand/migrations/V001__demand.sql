-- F1：需求发布与报价比选（docs/redesign/03-App实现任务计划.md §22，契约 contracts/openapi-demand.yaml 1.0.0）。
-- 客户发布需求 → 服务商报价（可改价、撤回）→ 客户比选、选定一家。停在“选定报价”：不建付款单、不建服务订单。
-- identity_user、identity_organization 由身份模块创建，本模块排在其后（ModuleFlywayConfiguration）。
-- 服务商资料（inspection_provider_profile）属于巡检模块，这里不引用它的表，只引用机构。
-- 金额一律存分，只做展示和比较。

CREATE TABLE demand (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- 发布人：登录账号。账号的默认组织是客户组织时记在组织名下（快照名称），否则是个人，两列都为空。
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    organization_id uuid REFERENCES identity_organization(id) ON DELETE RESTRICT,
    organization_name varchar(160),
    type varchar(16) NOT NULL
        CHECK (type IN ('INSPECTION', 'AERIAL', 'SURVEY', 'MAINTENANCE', 'TECH_SUPPORT')),
    -- 草稿可以不完整：提交时由服务校验城市、地点、范围、交付物、邀请都齐了。
    city_code varchar(16),
    city_name varchar(40),
    address varchar(80),
    scope_text varchar(60),
    -- 期望完成日期（北京时间的日期）；为空表示时间可协商。
    due_date date,
    budget_min_minor bigint CHECK (budget_min_minor >= 0),
    budget_max_minor bigint CHECK (budget_max_minor >= 1),
    note varchar(500),
    -- MATCH 平台匹配（发布城市里有服务商资料的服务商都能看到）；INVITE 只有被邀请的 1–3 家能看到。
    quote_scope varchar(8) NOT NULL DEFAULT 'MATCH' CHECK (quote_scope IN ('MATCH', 'INVITE')),
    -- DRAFT 草稿；OPEN 待报价；QUOTED 报价中（至少一份有效报价）；CHOSEN 已选定；CLOSED 已关闭。
    status varchar(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'OPEN', 'QUOTED', 'CHOSEN', 'CLOSED')),
    submitted_at timestamptz,
    -- 报价截止：提交后 72 小时。之后不再收新报价，也不能改价。
    quote_deadline timestamptz,
    chosen_quote_id uuid,
    chosen_quote_version integer,
    chosen_at timestamptz,
    closed_at timestamptz,
    -- NO_QUOTES 报价期内没有服务商报价（定时任务关闭）；CANCELLED 发布人取消。
    close_reason varchar(16) CHECK (close_reason IN ('NO_QUOTES', 'CANCELLED')),
    -- 新建时带的幂等键和请求摘要：同一个键重放返回同一份需求，换了内容拒绝。
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, idempotency_key),
    CONSTRAINT ck_demand_organization CHECK ((organization_id IS NULL) = (organization_name IS NULL)),
    CONSTRAINT ck_demand_city CHECK ((city_code IS NULL) = (city_name IS NULL)),
    CONSTRAINT ck_demand_budget CHECK (
        budget_min_minor IS NULL OR budget_max_minor IS NULL OR budget_min_minor <= budget_max_minor
    ),
    -- 提交过的需求：城市、地点、范围、提交时间、截止时间都有。
    CONSTRAINT ck_demand_submitted CHECK (
        status = 'DRAFT'
        OR (submitted_at IS NOT NULL AND quote_deadline IS NOT NULL AND city_code IS NOT NULL
            AND address IS NOT NULL AND scope_text IS NOT NULL)
    ),
    CONSTRAINT ck_demand_draft CHECK ((status = 'DRAFT') = (submitted_at IS NULL)),
    CONSTRAINT ck_demand_chosen CHECK (
        (status = 'CHOSEN') = (chosen_quote_id IS NOT NULL AND chosen_quote_version IS NOT NULL
                               AND chosen_at IS NOT NULL)
    ),
    CONSTRAINT ck_demand_closed CHECK (
        (status = 'CLOSED') = (closed_at IS NOT NULL AND close_reason IS NOT NULL)
    )
);

CREATE INDEX idx_demand_account ON demand (account_id, created_at DESC, id);
-- 服务商找能看到的需求、定时任务找过了截止时间还没人报价的需求。
CREATE INDEX idx_demand_open_city ON demand (city_code, status, quote_deadline) WHERE status IN ('OPEN', 'QUOTED');

-- 需要的交付物：按服务类型的白名单（服务校验类型和交付物对得上）。
CREATE TABLE demand_deliverable (
    demand_id uuid NOT NULL REFERENCES demand(id) ON DELETE CASCADE,
    code varchar(32) NOT NULL
        CHECK (code IN ('INSPECTION_REPORT', 'ORTHOPHOTO', 'HAZARD_LIST', 'MODEL_3D', 'VIDEO_FOOTAGE', 'PHOTOS',
                        'PANORAMA', 'EDITED_FILM', 'POINT_CLOUD', 'TOPOGRAPHIC_MAP', 'SURVEY_REPORT',
                        'REPAIR_RECORD', 'TEST_REPORT', 'PARTS_LIST', 'MAINTENANCE_ADVICE', 'SERVICE_RECORD',
                        'ISSUE_LIST', 'OPERATION_TRAINING', 'SOLUTION_ADVICE')),
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (demand_id, code)
);

-- “仅邀请指定服务商”时被邀请的服务商（机构），最多 3 家（服务校验）。
CREATE TABLE demand_invitation (
    demand_id uuid NOT NULL REFERENCES demand(id) ON DELETE CASCADE,
    provider_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (demand_id, provider_id)
);

CREATE INDEX idx_demand_invitation_provider ON demand_invitation (provider_id, demand_id);

-- 报价：一家服务商对一份需求只有一行（报价 ID 稳定），改价时覆盖内容、版本号加 1（客户看最新版，
-- 选定时带上版本号，版本变了就拒绝）；每一版的金额和工期记在 demand_event 里留痕。
CREATE TABLE demand_quote (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    demand_id uuid NOT NULL REFERENCES demand(id) ON DELETE RESTRICT,
    provider_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    -- ACTIVE 有效；WITHDRAWN 服务商撤回（截止前可以再报，版本继续加）；CHOSEN 已选中；
    -- NOT_CHOSEN 客户选了别家；CLOSED 需求被取消或关闭。“已过期”（过了有效期）按时间算，不落库。
    status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'WITHDRAWN', 'CHOSEN', 'NOT_CHOSEN', 'CLOSED')),
    quote_version integer NOT NULL DEFAULT 1 CHECK (quote_version >= 1),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    method varchar(40) NOT NULL,
    schedule_text varchar(60) NOT NULL,
    duration_days integer NOT NULL CHECK (duration_days BETWEEN 1 AND 3650),
    deliverables_text varchar(120) NOT NULL,
    valid_until timestamptz NOT NULL,
    -- 第一次报价的时间（“综合”排序按它），最近一次改价或撤回的时间。
    first_submitted_at timestamptz NOT NULL,
    revised_at timestamptz NOT NULL,
    submitted_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (demand_id, provider_id)
);

CREATE INDEX idx_demand_quote_demand ON demand_quote (demand_id, first_submitted_at, id);
CREATE INDEX idx_demand_quote_provider ON demand_quote (provider_id, demand_id);

-- 状态变化记录，只追加：谁（账号或服务商操作员；定时任务没有操作人）、什么时候、做了什么；报价记版本和金额。
CREATE TABLE demand_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    demand_id uuid NOT NULL REFERENCES demand(id) ON DELETE CASCADE,
    quote_id uuid REFERENCES demand_quote(id) ON DELETE RESTRICT,
    event_type varchar(24) NOT NULL
        CHECK (event_type IN ('CREATED', 'UPDATED', 'SUBMITTED', 'QUOTED', 'QUOTE_REVISED', 'QUOTE_WITHDRAWN',
                              'CHOSEN', 'CANCELLED', 'CLOSED_NO_QUOTES')),
    actor_id uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    quote_version integer,
    amount_minor bigint,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_demand_event_actor CHECK (actor_id IS NOT NULL OR event_type = 'CLOSED_NO_QUOTES'),
    CONSTRAINT ck_demand_event_quote CHECK (
        (event_type IN ('QUOTED', 'QUOTE_REVISED', 'QUOTE_WITHDRAWN', 'CHOSEN')) = (quote_id IS NOT NULL)
    )
);

CREATE INDEX idx_demand_event_demand ON demand_event (demand_id, occurred_at, id);
