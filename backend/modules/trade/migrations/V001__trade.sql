-- 统一交易模块（E 步，草案 2026-11-18，契约 contracts/openapi-trade.yaml）。
-- 渠道：微信支付“平台收付通”普通支付。每笔钱直接进商家（二级商户）自己的商户号，平台不经手资金，不分账、不抽佣。
-- 订单状态只由服务端根据渠道结果改：付款只认验签后的回调或主动查单。金额一律存分。
-- identity_user、identity_organization 由身份模块创建；mall_offer 等由商城模块创建，本模块排在它们之后（ModuleFlywayConfiguration）。
-- 收货人姓名、手机号、地址是敏感资料：只存在订单的配送快照里，查询都以买家或卖家机构为条件。

-- 商家的微信收款账户（二级商户号）。没有有效账户的商家不能下单。二级商户进件一期在微信服务商后台人工完成。
CREATE TABLE trade_merchant_account (
    organization_id uuid PRIMARY KEY REFERENCES identity_organization(id) ON DELETE RESTRICT,
    channel varchar(16) NOT NULL DEFAULT 'WECHAT' CHECK (channel = 'WECHAT'),
    sub_mchid varchar(32) NOT NULL CHECK (sub_mchid ~ '^[0-9]{8,32}$'),
    active boolean NOT NULL DEFAULT true,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE trade_order (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- 给人看的订单编号，如 MO20261118 + 8 位随机数。
    order_no varchar(24) NOT NULL UNIQUE,
    buyer_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    merchant_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    merchant_kind varchar(24) NOT NULL
        CHECK (merchant_kind IN ('FACTORY_DIRECT', 'AUTHORIZED_DEALER', 'LOCAL_STORE')),
    -- 下单时的商家名称和收款账户快照，之后改名、换账户不影响这张单的退款。
    merchant_name varchar(120) NOT NULL,
    sub_mchid varchar(32) NOT NULL,
    source varchar(16) NOT NULL CHECK (source IN ('MALL')),
    city_code varchar(16) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING_PAYMENT'
        CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'SHIPPED', 'COMPLETED', 'CLOSED',
                          'REFUNDING', 'REFUNDED', 'REFUND_FAILED')),
    delivery_mode varchar(16) NOT NULL CHECK (delivery_mode IN ('EXPRESS', 'PICKUP')),
    goods_minor bigint NOT NULL CHECK (goods_minor > 0),
    shipping_minor bigint NOT NULL DEFAULT 0 CHECK (shipping_minor >= 0),
    payable_minor bigint NOT NULL CHECK (payable_minor > 0),
    paid_minor bigint NOT NULL DEFAULT 0 CHECK (paid_minor >= 0),
    refunded_minor bigint NOT NULL DEFAULT 0 CHECK (refunded_minor >= 0),
    currency char(3) NOT NULL DEFAULT 'CNY' CHECK (currency = 'CNY'),
    buyer_note varchar(50),
    pay_before timestamptz NOT NULL,
    paid_at timestamptz,
    shipped_at timestamptz,
    completed_at timestamptz,
    closed_at timestamptz,
    -- 买家提交订单带的幂等键和请求摘要：同一个键重放返回同一张单，换内容拒绝。
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (buyer_id, idempotency_key),
    CHECK (payable_minor = goods_minor + shipping_minor),
    CHECK (paid_minor = 0 OR paid_minor = payable_minor),
    CHECK (refunded_minor <= paid_minor),
    CHECK (delivery_mode = 'EXPRESS' OR shipping_minor = 0),
    CHECK ((status = 'PENDING_PAYMENT') = (paid_at IS NULL AND closed_at IS NULL)),
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    CHECK (status IN ('PENDING_PAYMENT', 'CLOSED') OR paid_at IS NOT NULL)
);

CREATE INDEX idx_trade_order_buyer ON trade_order (buyer_id, created_at DESC, id);
CREATE INDEX idx_trade_order_merchant ON trade_order (merchant_id, status, created_at DESC, id);
-- 关闭超时未付款订单、自动确认收货的定时任务用。
CREATE INDEX idx_trade_order_unpaid ON trade_order (pay_before) WHERE status = 'PENDING_PAYMENT';
CREATE INDEX idx_trade_order_shipped ON trade_order (shipped_at) WHERE status = 'SHIPPED';

-- 下单时的商品快照。
CREATE TABLE trade_order_line (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL REFERENCES trade_order(id) ON DELETE RESTRICT,
    product_id uuid NOT NULL,
    offer_id uuid NOT NULL,
    product_name varchar(160) NOT NULL,
    sku_name varchar(80) NOT NULL,
    cover_url varchar(500),
    unit_amount_minor bigint NOT NULL CHECK (unit_amount_minor > 0),
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    subtotal_minor bigint NOT NULL CHECK (subtotal_minor = unit_amount_minor * quantity),
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_trade_order_line_order ON trade_order_line (order_id, sort_order, id);

-- 配送快照：快递时的收货人和地址，或自提点；发货后的物流单号；自提订单付款后的取货码。
CREATE TABLE trade_order_delivery (
    order_id uuid PRIMARY KEY REFERENCES trade_order(id) ON DELETE RESTRICT,
    mode varchar(16) NOT NULL CHECK (mode IN ('EXPRESS', 'PICKUP')),
    recipient_name varchar(20),
    phone varchar(11),
    province_name varchar(40),
    city_name varchar(40),
    address_detail varchar(80),
    pickup_point varchar(120),
    pickup_code char(6),
    carrier_name varchar(40),
    tracking_no varchar(40),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (mode <> 'EXPRESS' OR (recipient_name IS NOT NULL AND phone IS NOT NULL
                                 AND city_name IS NOT NULL AND address_detail IS NOT NULL)),
    CHECK (mode <> 'PICKUP' OR pickup_point IS NOT NULL),
    CHECK ((carrier_name IS NULL) = (tracking_no IS NULL))
);

-- 一次发起支付是一张支付单（微信的 out_trade_no）。一张订单同时只有一张没关闭的支付单。
CREATE TABLE trade_payment (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL REFERENCES trade_order(id) ON DELETE RESTRICT,
    channel varchar(16) NOT NULL CHECK (channel IN ('WECHAT_APP')),
    -- 微信要求 ≤32 位，只含数字、字母和 _-*。
    out_trade_no varchar(32) NOT NULL UNIQUE CHECK (out_trade_no ~ '^[0-9A-Za-z_*-]{6,32}$'),
    sub_mchid varchar(32) NOT NULL,
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    status varchar(16) NOT NULL DEFAULT 'CREATED'
        CHECK (status IN ('CREATED', 'PREPAID', 'SUCCEEDED', 'CLOSED', 'MISMATCH')),
    prepay_id varchar(64),
    prepay_expires_at timestamptz,
    transaction_id varchar(32) UNIQUE,
    succeeded_at timestamptz,
    closed_at timestamptz,
    idempotency_key uuid NOT NULL,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (order_id, idempotency_key),
    CHECK ((status = 'SUCCEEDED') = (succeeded_at IS NOT NULL)),
    CHECK (status <> 'SUCCEEDED' OR transaction_id IS NOT NULL),
    CHECK (status <> 'PREPAID' OR prepay_id IS NOT NULL)
);

CREATE UNIQUE INDEX uq_trade_payment_open
    ON trade_payment (order_id)
    WHERE status IN ('CREATED', 'PREPAID');
-- 一张订单最多一张付款成功的支付单。
CREATE UNIQUE INDEX uq_trade_payment_succeeded
    ON trade_payment (order_id)
    WHERE status = 'SUCCEEDED';

-- 退款单（微信的 out_refund_no，≤64 位）。重试沿用同一个退款单号，微信保证同一单号只退一次。
CREATE TABLE trade_refund (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL REFERENCES trade_order(id) ON DELETE RESTRICT,
    payment_id uuid NOT NULL REFERENCES trade_payment(id) ON DELETE RESTRICT,
    out_refund_no varchar(64) NOT NULL UNIQUE CHECK (out_refund_no ~ '^[0-9A-Za-z_|*@-]{6,64}$'),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    reason varchar(80) NOT NULL,
    -- 谁发起的：BUYER_CANCEL 买家取消未发货订单；MERCHANT 商家受权接口。
    initiator varchar(16) NOT NULL CHECK (initiator IN ('BUYER_CANCEL', 'MERCHANT')),
    requested_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    status varchar(16) NOT NULL DEFAULT 'REQUESTED'
        CHECK (status IN ('REQUESTED', 'PROCESSING', 'SUCCEEDED', 'CLOSED', 'ABNORMAL')),
    refund_id varchar(32) UNIQUE,
    succeeded_at timestamptz,
    idempotency_key uuid NOT NULL,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (order_id, idempotency_key),
    CHECK ((status = 'SUCCEEDED') = (succeeded_at IS NOT NULL))
);

CREATE INDEX idx_trade_refund_order ON trade_refund (order_id, created_at, id);

-- 收到的每一条渠道回调。只存验签和处理的结果、摘要，不存解密后的明文。同一个通知 ID 只处理一次。
CREATE TABLE trade_channel_notification (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    channel varchar(16) NOT NULL CHECK (channel = 'WECHAT'),
    notification_id varchar(64) NOT NULL,
    event_type varchar(40) NOT NULL,
    signature_valid boolean NOT NULL,
    out_trade_no varchar(32),
    out_refund_no varchar(64),
    -- RECEIVED 收到；APPLIED 已推进订单；IGNORED 重复或与订单无关；REJECTED 验签或核对失败。
    outcome varchar(16) NOT NULL CHECK (outcome IN ('RECEIVED', 'APPLIED', 'IGNORED', 'REJECTED')),
    detail varchar(200),
    received_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (channel, notification_id)
);

-- 每日账单导入和逐笔核对的结果，按日期和子商户号一行，差异另表。
CREATE TABLE trade_reconciliation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    bill_date date NOT NULL,
    sub_mchid varchar(32) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('IMPORTED', 'FAILED')),
    matched_count integer NOT NULL DEFAULT 0 CHECK (matched_count >= 0),
    failure_reason varchar(200),
    imported_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (bill_date, sub_mchid)
);

CREATE TABLE trade_reconciliation_difference (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reconciliation_id uuid NOT NULL REFERENCES trade_reconciliation(id) ON DELETE RESTRICT,
    kind varchar(24) NOT NULL
        CHECK (kind IN ('ONLY_IN_CHANNEL', 'ONLY_IN_PLATFORM', 'AMOUNT_MISMATCH', 'STATUS_MISMATCH')),
    out_trade_no varchar(32),
    out_refund_no varchar(64),
    platform_amount_minor bigint,
    channel_amount_minor bigint,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_trade_reconciliation_difference
    ON trade_reconciliation_difference (reconciliation_id, id);

-- 订单状态变化记录，只追加：谁、什么时候、从哪到哪、因为什么（命令、回调、定时任务）。
CREATE TABLE trade_state_transition (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL REFERENCES trade_order(id) ON DELETE RESTRICT,
    from_status varchar(24),
    to_status varchar(24) NOT NULL,
    cause varchar(24) NOT NULL
        CHECK (cause IN ('SUBMIT', 'PAYMENT_NOTIFIED', 'PAYMENT_QUERIED', 'BUYER_CANCEL', 'TIMEOUT',
                         'SHIPMENT', 'PICKUP', 'RECEIPT', 'AUTO_RECEIPT', 'REFUND_NOTIFIED',
                         'REFUND_QUERIED', 'MERCHANT_REFUND')),
    actor_id uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_trade_state_transition_order ON trade_state_transition (order_id, occurred_at, id);
