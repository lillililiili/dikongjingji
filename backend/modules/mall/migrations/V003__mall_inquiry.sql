-- F4：询价商品的询价单、商家报价、比选（docs/redesign/03-App实现任务计划.md §25，契约 contracts/openapi-mall-inquiry.yaml 1.0.0）。
-- 询价商品（sale_mode = INQUIRY）没有明码价：客户发询价单给 1–3 家商家，商家报价，客户选一份后按报价下一张普通商城订单。
-- 下单走统一交易模块（订单来源 MALL），这里只在报价上记下用它下过的那张订单（交易模块的表不引用）。
-- 金额一律存分。

CREATE TABLE mall_inquiry (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    -- 询价身份（只给客户自己看，商家看不到）：默认组织是客户组织时记在组织名下，否则个人；contact_name 是账号的显示名。
    buyer_kind varchar(16) NOT NULL CHECK (buyer_kind IN ('ORGANIZATION', 'PERSONAL')),
    buyer_organization_id uuid REFERENCES identity_organization(id) ON DELETE RESTRICT,
    buyer_organization_name varchar(160),
    buyer_contact_name varchar(80) NOT NULL,
    product_id uuid NOT NULL REFERENCES mall_product(id) ON DELETE RESTRICT,
    product_name varchar(160) NOT NULL,
    cover_url varchar(500),
    -- 配置和用途说明（必填）。
    requirement varchar(200) NOT NULL,
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    -- 交付城市（提交时的当前城市）和期望交付日期（可不填）。
    city_code varchar(16) NOT NULL,
    expected_date date,
    -- OPEN 待报价；QUOTED 报价中（至少一份有效报价）；CHOSEN 已选定；CLOSED 已关闭（见 close_reason）。
    status varchar(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'QUOTED', 'CHOSEN', 'CLOSED')),
    -- CUSTOMER 客户关闭；NO_QUOTES 截止时没有任何报价；QUOTES_LAPSED 截止后报价都已失效（过期、撤回）还没有选。
    close_reason varchar(16) CHECK (close_reason IN ('CUSTOMER', 'NO_QUOTES', 'QUOTES_LAPSED')),
    quote_deadline timestamptz NOT NULL,
    chosen_quote_id uuid,
    chosen_at timestamptz,
    closed_at timestamptz,
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, idempotency_key),
    CONSTRAINT ck_mall_inquiry_buyer CHECK (
        (buyer_kind = 'ORGANIZATION') = (buyer_organization_id IS NOT NULL AND buyer_organization_name IS NOT NULL)
    ),
    CONSTRAINT ck_mall_inquiry_chosen CHECK ((status = 'CHOSEN') = (chosen_quote_id IS NOT NULL AND chosen_at IS NOT NULL)),
    CONSTRAINT ck_mall_inquiry_closed CHECK ((status = 'CLOSED') = (close_reason IS NOT NULL AND closed_at IS NOT NULL))
);

CREATE INDEX idx_mall_inquiry_account ON mall_inquiry (account_id, updated_at DESC, id);
CREATE INDEX idx_mall_inquiry_open ON mall_inquiry (status, quote_deadline) WHERE status IN ('OPEN', 'QUOTED');

-- 发给哪些商家：所选的询价供给，一家商家一个。
CREATE TABLE mall_inquiry_invitation (
    inquiry_id uuid NOT NULL REFERENCES mall_inquiry(id) ON DELETE CASCADE,
    offer_id uuid NOT NULL REFERENCES mall_offer(id) ON DELETE RESTRICT,
    merchant_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    merchant_name varchar(160) NOT NULL,
    merchant_kind varchar(24) NOT NULL CHECK (merchant_kind IN ('FACTORY_DIRECT', 'AUTHORIZED_DEALER', 'LOCAL_STORE')),
    -- INVITED 待报价；QUOTED 已报价；DECLINED 商家拒绝（或撤回了报价）。
    status varchar(16) NOT NULL DEFAULT 'INVITED' CHECK (status IN ('INVITED', 'QUOTED', 'DECLINED')),
    decline_reason varchar(80),
    declined_at timestamptz,
    sort_order integer NOT NULL DEFAULT 0,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (inquiry_id, offer_id),
    UNIQUE (inquiry_id, merchant_id),
    CONSTRAINT ck_mall_inquiry_invitation_declined CHECK (
        (status = 'DECLINED') = (decline_reason IS NOT NULL AND declined_at IS NOT NULL)
    )
);

CREATE INDEX idx_mall_inquiry_invitation_merchant ON mall_inquiry_invitation (merchant_id, created_at DESC, inquiry_id);

-- 报价：每一版一行，只追加（改价是新的一版，旧版变 SUPERSEDED）。
-- ACTIVE 有效；SUPERSEDED 被新一版取代；EXPIRED 过了有效期；WITHDRAWN 商家拒绝时撤回；CHOSEN 被选中；LAPSED 选了别家后失效。
CREATE TABLE mall_inquiry_quote (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    inquiry_id uuid NOT NULL REFERENCES mall_inquiry(id) ON DELETE CASCADE,
    offer_id uuid NOT NULL REFERENCES mall_offer(id) ON DELETE RESTRICT,
    merchant_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    quote_version integer NOT NULL CHECK (quote_version >= 1),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'SUPERSEDED', 'EXPIRED', 'WITHDRAWN', 'CHOSEN', 'LAPSED')),
    unit_amount_minor bigint NOT NULL CHECK (unit_amount_minor > 0),
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    total_minor bigint NOT NULL,
    model_text varchar(60) NOT NULL,
    lead_days integer NOT NULL CHECK (lead_days BETWEEN 1 AND 365),
    includes_text varchar(120),
    valid_until timestamptz NOT NULL,
    quoted_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    quoted_at timestamptz NOT NULL,
    -- 用这份报价最近下的那张商城订单（交易模块的订单 ID）。订单还没关闭时不能再下第二张。
    last_order_id uuid,
    last_ordered_at timestamptz,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (inquiry_id, merchant_id, quote_version),
    CONSTRAINT ck_mall_inquiry_quote_total CHECK (total_minor = unit_amount_minor * quantity),
    CONSTRAINT ck_mall_inquiry_quote_validity CHECK (valid_until > quoted_at),
    CONSTRAINT ck_mall_inquiry_quote_order CHECK ((last_order_id IS NULL) = (last_ordered_at IS NULL)),
    CONSTRAINT ck_mall_inquiry_quote_ordered CHECK (last_order_id IS NULL OR status = 'CHOSEN')
);

CREATE INDEX idx_mall_inquiry_quote_inquiry ON mall_inquiry_quote (inquiry_id, status, quoted_at, id);
-- 每家商家同时只有一份有效（或已选中）的报价。
CREATE UNIQUE INDEX uq_mall_inquiry_quote_live ON mall_inquiry_quote (inquiry_id, merchant_id)
    WHERE status IN ('ACTIVE', 'CHOSEN');

ALTER TABLE mall_inquiry
    ADD CONSTRAINT fk_mall_inquiry_chosen_quote FOREIGN KEY (chosen_quote_id) REFERENCES mall_inquiry_quote(id)
        ON DELETE RESTRICT;

-- 状态变化记录，只追加：谁（客户账号、商家操作员；定时任务没有操作人）、什么时候、做了什么。
CREATE TABLE mall_inquiry_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    inquiry_id uuid NOT NULL REFERENCES mall_inquiry(id) ON DELETE CASCADE,
    quote_id uuid REFERENCES mall_inquiry_quote(id) ON DELETE CASCADE,
    event_type varchar(24) NOT NULL
        CHECK (event_type IN ('SUBMITTED', 'QUOTED', 'QUOTE_REVISED', 'DECLINED', 'QUOTE_EXPIRED', 'CHOSEN', 'CLOSED',
                              'CLOSED_NO_QUOTES', 'CLOSED_QUOTES_LAPSED', 'ORDERED')),
    actor_id uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    order_id uuid,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_mall_inquiry_event_actor CHECK (
        actor_id IS NOT NULL OR event_type IN ('QUOTE_EXPIRED', 'CLOSED_NO_QUOTES', 'CLOSED_QUOTES_LAPSED')
    ),
    CONSTRAINT ck_mall_inquiry_event_order CHECK ((event_type = 'ORDERED') = (order_id IS NOT NULL))
);

CREATE INDEX idx_mall_inquiry_event ON mall_inquiry_event (inquiry_id, occurred_at, id);
