-- F3：设备租赁的询价、报价、合同、租赁订单、交接与归还（docs/redesign/03-App实现任务计划.md §24，
-- 契约 contracts/openapi-rental.yaml 1.0.0）。租金经统一交易模块付给出租的商家（订单来源 RENTAL），本模块只记业务单。
-- identity_user、identity_organization 由身份模块创建，本模块排在巡检之后（ModuleFlywayConfiguration）。
-- 商城供给、收货地址、交易订单都属于别的模块：这里只存它们的 ID 和确认时的快照，不建外键。
-- 金额一律存分。

CREATE TABLE rental_inquiry (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    -- 询价身份：账号的默认组织是客户组织时记在组织名下，否则是个人；contact_name 是账号的显示名（经办人）。
    lessee_kind varchar(16) NOT NULL CHECK (lessee_kind IN ('ORGANIZATION', 'PERSONAL')),
    lessee_organization_id uuid REFERENCES identity_organization(id) ON DELETE RESTRICT,
    lessee_organization_name varchar(160),
    lessee_contact_name varchar(80) NOT NULL,
    -- 设备：商城的租赁供给（一个商家的一种设备），提交时的快照。
    offer_id uuid NOT NULL,
    product_id uuid NOT NULL,
    product_name varchar(160) NOT NULL,
    sku_name varchar(80) NOT NULL,
    cover_url varchar(500),
    merchant_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    merchant_name varchar(160) NOT NULL,
    reference_amount_minor bigint CHECK (reference_amount_minor > 0),
    reference_unit varchar(16) NOT NULL CHECK (reference_unit IN ('DAY', 'HALF_DAY', 'ITEM')),
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 10),
    -- 期望起租日（北京时间的日期）和天数。
    start_date date NOT NULL,
    days integer NOT NULL CHECK (days BETWEEN 1 AND 90),
    handover_mode varchar(16) NOT NULL CHECK (handover_mode IN ('DELIVERY', 'PICKUP')),
    return_mode varchar(16) NOT NULL CHECK (return_mode IN ('COLLECT', 'DROP_OFF')),
    -- 使用地点：从收货地址里选，联系人和手机号是地址的收货人（快照，之后改地址不影响）。
    address_id uuid NOT NULL,
    recipient_name varchar(20) NOT NULL,
    recipient_phone varchar(11) NOT NULL,
    province_name varchar(40) NOT NULL,
    city_code varchar(16) NOT NULL,
    city_name varchar(40) NOT NULL,
    address_detail varchar(80) NOT NULL,
    note varchar(200),
    -- PENDING 待报价；QUOTED 待确认；EXPIRED 报价已过期（商家可以再报）；CONFIRMED 已确认合同（有租赁订单）；
    -- DECLINED 商家拒绝；CLOSED 已关闭（客户关闭，或提交 7 天内没有报价）。
    status varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'QUOTED', 'EXPIRED', 'CONFIRMED', 'DECLINED', 'CLOSED')),
    close_reason varchar(16) CHECK (close_reason IN ('CUSTOMER', 'NO_QUOTE')),
    decline_reason varchar(80),
    current_quote_version integer CHECK (current_quote_version >= 1),
    -- 报价截止：提交后 7 天，之前没有报价就关闭。
    quote_deadline timestamptz NOT NULL,
    closed_at timestamptz,
    declined_at timestamptz,
    confirmed_at timestamptz,
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, idempotency_key),
    CONSTRAINT ck_rental_inquiry_lessee CHECK (
        (lessee_kind = 'ORGANIZATION') = (lessee_organization_id IS NOT NULL AND lessee_organization_name IS NOT NULL)
    ),
    CONSTRAINT ck_rental_inquiry_quoted CHECK (
        status NOT IN ('QUOTED', 'EXPIRED', 'CONFIRMED') OR current_quote_version IS NOT NULL
    ),
    CONSTRAINT ck_rental_inquiry_closed CHECK ((status = 'CLOSED') = (close_reason IS NOT NULL AND closed_at IS NOT NULL)),
    CONSTRAINT ck_rental_inquiry_declined CHECK (
        (status = 'DECLINED') = (decline_reason IS NOT NULL AND declined_at IS NOT NULL)
    ),
    CONSTRAINT ck_rental_inquiry_confirmed CHECK ((status = 'CONFIRMED') = (confirmed_at IS NOT NULL))
);

CREATE INDEX idx_rental_inquiry_account ON rental_inquiry (account_id, updated_at DESC, id);
CREATE INDEX idx_rental_inquiry_merchant ON rental_inquiry (merchant_id, created_at DESC, id);
-- 定时任务：过了报价截止还没有报价的、报价过了有效期的。
CREATE INDEX idx_rental_inquiry_open ON rental_inquiry (status, quote_deadline) WHERE status IN ('PENDING', 'QUOTED');

-- 报价：每一版一行，只追加（改价是新的一版）；询价单的 current_quote_version 指向最新一版。
CREATE TABLE rental_quote (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    inquiry_id uuid NOT NULL REFERENCES rental_inquiry(id) ON DELETE CASCADE,
    quote_version integer NOT NULL CHECK (quote_version >= 1),
    daily_rent_minor bigint NOT NULL CHECK (daily_rent_minor > 0),
    days integer NOT NULL CHECK (days BETWEEN 1 AND 90),
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 10),
    rent_minor bigint NOT NULL,
    total_minor bigint NOT NULL,
    -- 商家确认这段时间可以出租；为 false 时客户不能确认合同。
    schedule_available boolean NOT NULL,
    valid_until timestamptz NOT NULL,
    terms varchar(2000) NOT NULL,
    quoted_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    quoted_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (inquiry_id, quote_version),
    CONSTRAINT ck_rental_quote_rent CHECK (rent_minor = daily_rent_minor * days * quantity),
    CONSTRAINT ck_rental_quote_total CHECK (total_minor >= rent_minor),
    CONSTRAINT ck_rental_quote_validity CHECK (valid_until > quoted_at)
);

-- 报价的其他费用行（可以为 0，如“市内送达和取回 · 含”）。
CREATE TABLE rental_quote_fee (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    quote_id uuid NOT NULL REFERENCES rental_quote(id) ON DELETE CASCADE,
    name varchar(20) NOT NULL,
    note varchar(40),
    amount_minor bigint NOT NULL CHECK (amount_minor >= 0),
    sort_order integer NOT NULL DEFAULT 0
);

CREATE INDEX idx_rental_quote_fee ON rental_quote_fee (quote_id, sort_order, id);

-- 租赁订单：确认合同时建，同时在交易模块建一张待付款的订单（来源 RENTAL，收款方是出租的商家）。
CREATE TABLE rental_order (
    id uuid PRIMARY KEY,
    inquiry_id uuid NOT NULL UNIQUE REFERENCES rental_inquiry(id) ON DELETE RESTRICT,
    quote_id uuid NOT NULL REFERENCES rental_quote(id) ON DELETE RESTRICT,
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    merchant_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    trade_order_id uuid NOT NULL UNIQUE,
    -- PENDING_PAYMENT 待付款；CLOSED 已关闭（未付款）；AWAITING_HANDOVER 待交接；HANDOVER_RECORDED 商家已录入交接、待客户确认；
    -- IN_USE 使用中（确认交接起计租）；RETURN_RECORDED 商家已录入归还、待客户确认；ENDED 已结束；CANCELLED 交接前商家取消（整单退款）。
    status varchar(24) NOT NULL DEFAULT 'PENDING_PAYMENT'
        CHECK (status IN ('PENDING_PAYMENT', 'CLOSED', 'AWAITING_HANDOVER', 'HANDOVER_RECORDED', 'IN_USE',
                          'RETURN_RECORDED', 'ENDED', 'CANCELLED')),
    total_minor bigint NOT NULL CHECK (total_minor > 0),
    pay_before timestamptz NOT NULL,
    paid_at timestamptz,
    closed_at timestamptz,
    rent_started_at timestamptz,
    ended_at timestamptz,
    cancelled_at timestamptz,
    cancel_reason varchar(80),
    refund_id uuid,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_rental_order_paid CHECK (status IN ('PENDING_PAYMENT', 'CLOSED') OR paid_at IS NOT NULL),
    CONSTRAINT ck_rental_order_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    CONSTRAINT ck_rental_order_cancelled CHECK (
        (status = 'CANCELLED') = (cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL AND refund_id IS NOT NULL)
    ),
    CONSTRAINT ck_rental_order_started CHECK (
        status NOT IN ('IN_USE', 'RETURN_RECORDED', 'ENDED') OR rent_started_at IS NOT NULL
    ),
    CONSTRAINT ck_rental_order_ended CHECK ((status = 'ENDED') = (ended_at IS NOT NULL))
);

CREATE INDEX idx_rental_order_account ON rental_order (account_id, created_at DESC, id);
CREATE INDEX idx_rental_order_merchant ON rental_order (merchant_id, created_at DESC, id);

-- 合同：确认时按报价和询价生成，之后不改。合同线下签署，这里没有文件。
CREATE TABLE rental_contract (
    order_id uuid PRIMARY KEY REFERENCES rental_order(id) ON DELETE CASCADE,
    contract_no varchar(24) NOT NULL UNIQUE,
    quote_id uuid NOT NULL REFERENCES rental_quote(id) ON DELETE RESTRICT,
    quote_version integer NOT NULL,
    lessee_kind varchar(16) NOT NULL CHECK (lessee_kind IN ('ORGANIZATION', 'PERSONAL')),
    lessee_organization_name varchar(160),
    lessee_contact_name varchar(80) NOT NULL,
    lessor_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    lessor_name varchar(160) NOT NULL,
    product_name varchar(160) NOT NULL,
    sku_name varchar(80) NOT NULL,
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 10),
    start_date date NOT NULL,
    end_date date NOT NULL,
    days integer NOT NULL CHECK (days BETWEEN 1 AND 90),
    daily_rent_minor bigint NOT NULL CHECK (daily_rent_minor > 0),
    rent_minor bigint NOT NULL,
    total_minor bigint NOT NULL,
    handover_mode varchar(16) NOT NULL CHECK (handover_mode IN ('DELIVERY', 'PICKUP')),
    return_mode varchar(16) NOT NULL CHECK (return_mode IN ('COLLECT', 'DROP_OFF')),
    recipient_name varchar(20) NOT NULL,
    recipient_phone varchar(11) NOT NULL,
    province_name varchar(40) NOT NULL,
    city_code varchar(16) NOT NULL,
    city_name varchar(40) NOT NULL,
    address_detail varchar(80) NOT NULL,
    terms varchar(2000) NOT NULL,
    confirmed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_rental_contract_period CHECK (end_date = start_date + days - 1),
    CONSTRAINT ck_rental_contract_rent CHECK (rent_minor = daily_rent_minor * days * quantity AND total_minor >= rent_minor)
);

-- 交接和归还记录：商家录入（客户确认之前可以改），客户确认后不再改。
CREATE TABLE rental_record (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL REFERENCES rental_order(id) ON DELETE CASCADE,
    kind varchar(16) NOT NULL CHECK (kind IN ('HANDOVER', 'RETURN')),
    occurred_at timestamptz NOT NULL,
    note varchar(200) NOT NULL,
    recorded_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    recorded_at timestamptz NOT NULL,
    confirmed_by uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    confirmed_at timestamptz,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (order_id, kind),
    CONSTRAINT ck_rental_record_confirmed CHECK ((confirmed_by IS NULL) = (confirmed_at IS NULL))
);

-- 状态变化记录，只追加：谁（账号、商家操作员；定时任务和交易回调没有操作人）、什么时候、做了什么。
CREATE TABLE rental_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    inquiry_id uuid NOT NULL REFERENCES rental_inquiry(id) ON DELETE CASCADE,
    order_id uuid REFERENCES rental_order(id) ON DELETE CASCADE,
    event_type varchar(24) NOT NULL
        CHECK (event_type IN ('SUBMITTED', 'QUOTED', 'QUOTE_REVISED', 'DECLINED', 'CLOSED', 'CLOSED_NO_QUOTE',
                              'QUOTE_EXPIRED', 'CONFIRMED', 'PAYMENT_CONFIRMED', 'PAYMENT_CLOSED',
                              'LATE_PAYMENT_REFUSED', 'HANDOVER_RECORDED', 'HANDOVER_CONFIRMED', 'RETURN_RECORDED',
                              'RETURN_CONFIRMED', 'CANCELLED')),
    actor_id uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    quote_version integer,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_rental_event_actor CHECK (
        actor_id IS NOT NULL
        OR event_type IN ('CLOSED_NO_QUOTE', 'QUOTE_EXPIRED', 'PAYMENT_CONFIRMED', 'PAYMENT_CLOSED', 'LATE_PAYMENT_REFUSED')
    ),
    CONSTRAINT ck_rental_event_order CHECK (
        (event_type IN ('PAYMENT_CONFIRMED', 'PAYMENT_CLOSED', 'LATE_PAYMENT_REFUSED', 'HANDOVER_RECORDED',
                        'HANDOVER_CONFIRMED', 'RETURN_RECORDED', 'RETURN_CONFIRMED', 'CANCELLED', 'CONFIRMED'))
        = (order_id IS NOT NULL)
    )
);

CREATE INDEX idx_rental_event_inquiry ON rental_event (inquiry_id, occurred_at, id);
