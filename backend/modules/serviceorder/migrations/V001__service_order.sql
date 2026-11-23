-- S4-3：通用服务订单（docs/redesign/03-App实现任务计划.md §28，契约 contracts/openapi-service-order.yaml 1.0.0）。
-- 航拍、测绘、设备维护、技术支持四类需求选定报价时由需求模块经端口建单；服务商出方案 → 客户确认 → 经统一交易模块一次付清
-- （来源 PRO_SERVICE，收款方是服务商）→ 开工 → 交付（可多次）→ 验收或退回整改；开工前取消、开工后协商终止。
-- identity_user、identity_organization 由身份模块创建，本模块排在巡检之后（ModuleFlywayConfiguration）。
-- 需求、报价、交易订单、交易退款都属于别的模块：这里只存它们的 ID 和建单时的快照，不建外键。金额一律存分。

CREATE TABLE service_order (
    id uuid PRIMARY KEY,
    -- SV + 北京日期 + 6 位数字。
    order_no varchar(24) NOT NULL UNIQUE,
    demand_id uuid NOT NULL UNIQUE,
    quote_id uuid NOT NULL,
    quote_version integer NOT NULL CHECK (quote_version >= 1),
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    -- 下单身份（需求的发布身份快照）：企业时有组织名；经办人是建单时账号的显示名。
    customer_kind varchar(16) NOT NULL CHECK (customer_kind IN ('ORGANIZATION', 'PERSONAL')),
    customer_organization_id uuid REFERENCES identity_organization(id) ON DELETE RESTRICT,
    customer_organization_name varchar(160),
    contact_name varchar(80) NOT NULL,
    provider_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    provider_name varchar(160) NOT NULL,
    type varchar(16) NOT NULL CHECK (type IN ('AERIAL', 'SURVEY', 'MAINTENANCE', 'TECH_SUPPORT')),
    title varchar(40) NOT NULL,
    city_code varchar(16) NOT NULL,
    city_name varchar(40) NOT NULL,
    address varchar(80) NOT NULL,
    scope_text varchar(60) NOT NULL,
    due_date date,
    -- 合同金额 = 选定那一版报价的总价；方案金额必须等于它，付款也是它。
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    -- 选定那一版报价的快照（对比项可以为空）。
    quote_method varchar(40) NOT NULL,
    quote_schedule varchar(60) NOT NULL,
    quote_duration_days integer NOT NULL CHECK (quote_duration_days BETWEEN 1 AND 3650),
    quote_deliverables varchar(120) NOT NULL,
    quote_team varchar(20),
    quote_night_work varchar(20),
    quote_report_hours integer CHECK (quote_report_hours BETWEEN 1 AND 720),
    quote_archive_days integer CHECK (quote_archive_days BETWEEN 0 AND 3650),
    quote_first_visit_date date,
    -- AWAITING_PLAN 待出方案；PLAN_PENDING 待确认方案；AWAITING_PAYMENT 待付款；AWAITING_START 待开工（已付款）；
    -- IN_SERVICE 服务中；DELIVERED 待验收；REWORK 整改中；COMPLETED 已完成；CANCELLED 已取消（开工前）；TERMINATED 协商终止（开工后）。
    status varchar(20) NOT NULL DEFAULT 'AWAITING_PLAN'
        CHECK (status IN ('AWAITING_PLAN', 'PLAN_PENDING', 'AWAITING_PAYMENT', 'AWAITING_START', 'IN_SERVICE',
                          'DELIVERED', 'REWORK', 'COMPLETED', 'CANCELLED', 'TERMINATED')),
    -- 最新一版方案的版本；还没出方案时为空。
    plan_version integer CHECK (plan_version >= 1),
    paid_at timestamptz,
    started_at timestamptz,
    completed_at timestamptz,
    closed_at timestamptz,
    closed_by varchar(16) CHECK (closed_by IN ('CUSTOMER', 'PROVIDER')),
    close_reason varchar(200),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_service_order_customer CHECK (
        (customer_kind = 'ORGANIZATION') = (customer_organization_id IS NOT NULL AND customer_organization_name IS NOT NULL)
    ),
    CONSTRAINT ck_service_order_plan CHECK (status = 'AWAITING_PLAN' OR status IN ('CANCELLED') OR plan_version IS NOT NULL),
    CONSTRAINT ck_service_order_paid CHECK (
        status NOT IN ('AWAITING_START', 'IN_SERVICE', 'DELIVERED', 'REWORK', 'COMPLETED', 'TERMINATED') OR paid_at IS NOT NULL
    ),
    CONSTRAINT ck_service_order_started CHECK (
        status NOT IN ('IN_SERVICE', 'DELIVERED', 'REWORK', 'COMPLETED', 'TERMINATED') OR started_at IS NOT NULL
    ),
    CONSTRAINT ck_service_order_completed CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT ck_service_order_closed CHECK (
        (status IN ('CANCELLED', 'TERMINATED')) = (closed_at IS NOT NULL AND closed_by IS NOT NULL AND close_reason IS NOT NULL)
    ),
    CONSTRAINT ck_service_order_terminated CHECK (status <> 'TERMINATED' OR closed_by = 'PROVIDER')
);

CREATE INDEX idx_service_order_account ON service_order (account_id, updated_at DESC, id);
CREATE INDEX idx_service_order_provider ON service_order (provider_id, updated_at DESC, id);

-- 报价明细（建单时的快照；报价没有明细时只有一行“服务费用”）。方案明细同此。
CREATE TABLE service_order_line (
    order_id uuid NOT NULL REFERENCES service_order(id) ON DELETE CASCADE,
    sort_order integer NOT NULL,
    name varchar(20) NOT NULL,
    quantity integer CHECK (quantity BETWEEN 1 AND 999),
    unit varchar(4),
    unit_amount_minor bigint CHECK (unit_amount_minor > 0),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    PRIMARY KEY (order_id, sort_order),
    CONSTRAINT ck_service_order_line_unit CHECK (
        (quantity IS NULL AND unit IS NULL AND unit_amount_minor IS NULL)
        OR (quantity IS NOT NULL AND unit IS NOT NULL AND unit_amount_minor IS NOT NULL
            AND amount_minor = quantity * unit_amount_minor)
    )
);

-- 方案：每一版一行，只追加；订单的 plan_version 指向最新一版。确认只发生在最新一版上。
CREATE TABLE service_order_plan (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL REFERENCES service_order(id) ON DELETE CASCADE,
    plan_version integer NOT NULL CHECK (plan_version >= 1),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    schedule_text varchar(60) NOT NULL,
    -- 交付约定 1–5 条，换行分隔（服务校验条数和每条长度）。
    terms_text varchar(320) NOT NULL,
    note varchar(500),
    issued_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    issued_at timestamptz NOT NULL,
    confirmed_by uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    confirmed_by_name varchar(80),
    confirmed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (order_id, plan_version),
    CONSTRAINT ck_service_order_plan_confirmed CHECK (
        (confirmed_at IS NULL) = (confirmed_by IS NULL) AND (confirmed_at IS NULL) = (confirmed_by_name IS NULL)
    )
);

-- 付款尝试：每次发起是一张交易订单（交易模块的 source_ref 就是这里的 id）。同一张服务订单同时最多一张待付、最多一张已付。
CREATE TABLE service_order_payment (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES service_order(id) ON DELETE CASCADE,
    trade_order_id uuid NOT NULL UNIQUE,
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    pay_before timestamptz NOT NULL,
    -- PENDING 待付；PAID 已付；CLOSED 没付就关了（超时或取消），或付晚了被退回（refused = true）。
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PAID', 'CLOSED')),
    refused boolean NOT NULL DEFAULT false,
    paid_at timestamptz,
    closed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_service_order_payment_paid CHECK ((status = 'PAID') = (paid_at IS NOT NULL)),
    CONSTRAINT ck_service_order_payment_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_service_order_payment_pending ON service_order_payment (order_id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX uq_service_order_payment_paid ON service_order_payment (order_id) WHERE status = 'PAID';

-- 退款：开工前取消的整笔退款（CANCEL）和协商终止时服务商退的款（TERMINATE）。进度在交易模块，按 trade_refund_id 读。
CREATE TABLE service_order_refund (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL REFERENCES service_order(id) ON DELETE CASCADE,
    trade_refund_id uuid NOT NULL UNIQUE,
    kind varchar(16) NOT NULL CHECK (kind IN ('CANCEL', 'TERMINATE')),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    reason varchar(200) NOT NULL,
    requested_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    requested_at timestamptz NOT NULL
);

CREATE INDEX idx_service_order_refund_order ON service_order_refund (order_id, requested_at, id);

-- 交付：第几次（round）交付一行。SUBMITTED 待验收；ACCEPTED 已验收；RETURNED 已退回整改（review_note 是原因）。
CREATE TABLE service_order_delivery (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL REFERENCES service_order(id) ON DELETE CASCADE,
    round integer NOT NULL CHECK (round >= 1),
    note varchar(1000) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'SUBMITTED' CHECK (status IN ('SUBMITTED', 'ACCEPTED', 'RETURNED')),
    delivered_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    delivered_at timestamptz NOT NULL,
    reviewed_by uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    reviewed_by_name varchar(80),
    reviewed_at timestamptz,
    review_note varchar(200),
    UNIQUE (order_id, round),
    CONSTRAINT ck_service_order_delivery_reviewed CHECK (
        (status = 'SUBMITTED') = (reviewed_at IS NULL)
        AND (reviewed_at IS NULL) = (reviewed_by IS NULL)
        AND (reviewed_at IS NULL) = (reviewed_by_name IS NULL)
    ),
    CONSTRAINT ck_service_order_delivery_returned CHECK ((status = 'RETURNED') = (review_note IS NOT NULL))
);

-- 交付文件：先上传（delivery_id 为空，服务商可以删），提交交付时挂到那一次交付上，之后不能删。
-- storage_key 只在服务端用，不出现在任何接口里。
CREATE TABLE service_order_file (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES service_order(id) ON DELETE CASCADE,
    delivery_id uuid REFERENCES service_order_delivery(id) ON DELETE RESTRICT,
    name varchar(120) NOT NULL,
    content_type varchar(100) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes > 0),
    sha256 char(64) NOT NULL,
    storage_key varchar(200) NOT NULL UNIQUE,
    uploaded_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    uploaded_at timestamptz NOT NULL
);

CREATE INDEX idx_service_order_file_order ON service_order_file (order_id, uploaded_at, id);

-- 客户的短时下载令牌（10 分钟）：令牌只在这张表里，链接不带登录也能打开；过期的读不到。
CREATE TABLE service_order_download (
    token uuid PRIMARY KEY,
    file_id uuid NOT NULL REFERENCES service_order_file(id) ON DELETE CASCADE,
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CONSTRAINT ck_service_order_download_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_service_order_download_expiry ON service_order_download (expires_at);

-- 订单记录，只追加：谁（客户、服务商操作员；交易回调没有操作人）、什么时候、做了什么。
CREATE TABLE service_order_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL REFERENCES service_order(id) ON DELETE CASCADE,
    event_type varchar(16) NOT NULL
        CHECK (event_type IN ('CREATED', 'PLAN_ISSUED', 'PLAN_REVISED', 'PLAN_CONFIRMED', 'PAID', 'STARTED', 'DELIVERED',
                              'ACCEPTED', 'RETURNED', 'CANCELLED', 'TERMINATED', 'LATE_PAYMENT_REFUSED')),
    actor_id uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    plan_version integer,
    round integer,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_service_order_event_actor CHECK (actor_id IS NOT NULL OR event_type IN ('PAID', 'LATE_PAYMENT_REFUSED'))
);

CREATE INDEX idx_service_order_event_order ON service_order_event (order_id, occurred_at, id);
