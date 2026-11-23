-- S4-2 商品售后与 S4-4 交易字段（docs/redesign/08-后续开发计划.md §5 A1、S4-4；契约 contracts/openapi-trade.yaml 0.6.0）。
-- 售后只针对商城订单（source = 'MALL'）。两类：七天无理由（未拆封，商品没标“不支持七天无理由”）和质量问题。
-- 平台只协助沟通，不裁决：同意、拒绝、验货都由卖出商品的商家（售后责任方）在工作台操作；退款经本模块原路退回。
-- 只加不改已有数据：新列都可空或有默认值，已有订单照常读写。

-- ---- 订单上的交易主体快照（S4-4）----
-- 下单时商城给的“从哪发货”“谁开票”“谁负责售后”，之后商家改供给不影响这张单。业务来源的订单为空。
-- participant_names：收费报名订单的参与人姓名，按报名顺序用顿号连接，只用来显示（报名单才是事实源）。
ALTER TABLE trade_order
    ADD COLUMN fulfilled_from varchar(250),
    ADD COLUMN invoice_issuer varchar(250),
    ADD COLUMN aftersale_party varchar(250),
    ADD COLUMN participant_names varchar(400);

-- 下单时这件商品是否支持七天无理由（商品可以标“不支持”）。已有订单行按支持处理（平台默认规则）。
ALTER TABLE trade_order_line
    ADD COLUMN no_reason_return boolean NOT NULL DEFAULT true;

-- ---- 售后单 ----
-- kind：RETURN_REFUND 退货退款（已发货、已自提或已收货的订单）；REFUND_ONLY 仅退款（已付款未发货、未自提的订单，
--   按 §19.7 的取消规则直接全额原路退回，不用商家同意）。
-- reason：NO_REASON_7D 七天无理由；NOT_AS_DESCRIBED 与描述不符；DAMAGED 商品有损坏（后两个算质量问题）；
--   NOT_WANTED 不想要了（只用于仅退款）。
-- status：
--   PENDING_REVIEW 等待商家处理（48 小时不处理自动同意）；
--   AWAITING_RETURN 待寄回（7 天内不填运单自动关闭）；
--   RETURN_SHIPPED 待商家收货（运单提交后 10 天商家不确认也不拒绝，按申请金额自动退款）；
--   REFUNDING 退款中；REFUNDED 已退款；REFUND_FAILED 退款异常（渠道没退成，人工处理）；
--   REJECTED 商家未同意；RECEIPT_REJECTED 验货未通过；REVOKED 买家撤销；CLOSED 逾期未寄回，已关闭。
CREATE TABLE trade_aftersale (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- 给人看的售后编号：AS + 北京日期 + 8 位随机数。
    aftersale_no varchar(24) NOT NULL UNIQUE,
    order_id uuid NOT NULL REFERENCES trade_order(id) ON DELETE RESTRICT,
    buyer_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    merchant_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    kind varchar(16) NOT NULL CHECK (kind IN ('RETURN_REFUND', 'REFUND_ONLY')),
    reason varchar(24) NOT NULL CHECK (reason IN ('NO_REASON_7D', 'NOT_AS_DESCRIBED', 'DAMAGED', 'NOT_WANTED')),
    description varchar(200),
    requested_minor bigint NOT NULL CHECK (requested_minor > 0),
    -- 实际退款金额：商家确认收货时定（可以少于申请金额，要写说明），仅退款时等于申请金额。
    refund_minor bigint CHECK (refund_minor IS NULL OR (refund_minor > 0 AND refund_minor <= requested_minor)),
    status varchar(20) NOT NULL DEFAULT 'PENDING_REVIEW'
        CHECK (status IN ('PENDING_REVIEW', 'AWAITING_RETURN', 'RETURN_SHIPPED', 'REFUNDING', 'REFUNDED',
                          'REFUND_FAILED', 'REJECTED', 'RECEIPT_REJECTED', 'REVOKED', 'CLOSED')),
    -- 商家同意时给的寄回地址（快照，取同意时填的或商家的默认寄回地址）。寄回人、电话、地址是商家的售后联系方式，
    -- 只给这张售后单的买家看。超时自动同意而商家又没设默认地址时为空，买家页面提示先联系商家。
    return_contact varchar(40),
    return_phone varchar(20),
    return_address varchar(200),
    return_note varchar(200),
    -- 买家填的退货运单；商家确认收货前可以改。
    return_carrier varchar(40),
    return_tracking_no varchar(40),
    -- 商家拒绝（审核时或验货时）的原因和说明。
    reject_reason varchar(100),
    merchant_note varchar(200),
    -- 经交易模块发起的退款单（trade_refund），到账或失败时据此推进售后单。
    refund_id uuid UNIQUE REFERENCES trade_refund(id) ON DELETE RESTRICT,
    -- 当前这一步的处理时限（等待商家处理、待寄回、待商家收货时有值）；定时任务按它自动推进。
    due_at timestamptz,
    submitted_at timestamptz NOT NULL,
    reviewed_at timestamptz,
    return_shipped_at timestamptz,
    received_at timestamptz,
    refunded_at timestamptz,
    closed_at timestamptz,
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (buyer_id, idempotency_key),
    CONSTRAINT ck_trade_aftersale_reason_by_kind
        CHECK ((kind = 'REFUND_ONLY') = (reason = 'NOT_WANTED')),
    CONSTRAINT ck_trade_aftersale_due
        CHECK ((status IN ('PENDING_REVIEW', 'AWAITING_RETURN', 'RETURN_SHIPPED')) = (due_at IS NOT NULL)),
    CONSTRAINT ck_trade_aftersale_waybill
        CHECK ((return_carrier IS NULL) = (return_tracking_no IS NULL)),
    CONSTRAINT ck_trade_aftersale_rejected
        CHECK (status NOT IN ('REJECTED', 'RECEIPT_REJECTED') OR reject_reason IS NOT NULL),
    CONSTRAINT ck_trade_aftersale_refund
        CHECK (status NOT IN ('REFUNDING', 'REFUNDED', 'REFUND_FAILED') OR refund_id IS NOT NULL)
);

-- 一张订单同时只有一张没结束的售后单。
CREATE UNIQUE INDEX uq_trade_aftersale_open
    ON trade_aftersale (order_id)
    WHERE status IN ('PENDING_REVIEW', 'AWAITING_RETURN', 'RETURN_SHIPPED', 'REFUNDING');
CREATE INDEX idx_trade_aftersale_buyer ON trade_aftersale (buyer_id, submitted_at DESC, id DESC);
CREATE INDEX idx_trade_aftersale_merchant ON trade_aftersale (merchant_id, status, submitted_at DESC, id DESC);
CREATE INDEX idx_trade_aftersale_order ON trade_aftersale (order_id, submitted_at DESC);
-- 定时任务：到了处理时限还没处理的。
CREATE INDEX idx_trade_aftersale_due ON trade_aftersale (due_at) WHERE due_at IS NOT NULL;

-- 售后凭证图片（商品照片、包装照片、其他凭证）。文件在对象存储端口里（开发环境是本机目录），这里只存元数据和存储键；
-- 不对外给存储地址，只经买家和商家的受权接口读。先上传（还没挂到售后单上），提交申请时挂上；没挂上的只有上传人能看。
CREATE TABLE trade_aftersale_image (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    uploaded_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    aftersale_id uuid REFERENCES trade_aftersale(id) ON DELETE RESTRICT,
    slot varchar(16) NOT NULL CHECK (slot IN ('PRODUCT', 'PACKAGE', 'OTHER')),
    content_type varchar(16) NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    size_bytes integer NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 5242880),
    sha256 char(64) NOT NULL,
    storage_key varchar(200) NOT NULL UNIQUE,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_trade_aftersale_image_aftersale ON trade_aftersale_image (aftersale_id, sort_order, id)
    WHERE aftersale_id IS NOT NULL;
CREATE INDEX idx_trade_aftersale_image_uploader ON trade_aftersale_image (uploaded_by, created_at DESC);

-- 售后单的每一步，只追加：谁（买家、商家操作员，或定时任务为空）、什么时候、从哪到哪、做了什么。
CREATE TABLE trade_aftersale_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aftersale_id uuid NOT NULL REFERENCES trade_aftersale(id) ON DELETE RESTRICT,
    action varchar(24) NOT NULL CHECK (action IN (
        'SUBMIT', 'AGREE', 'AUTO_AGREE', 'REJECT', 'REVOKE', 'SHIP_RETURN', 'EDIT_RETURN', 'CLOSE_OVERDUE',
        'CONFIRM_RECEIPT', 'AUTO_REFUND', 'REJECT_RECEIPT', 'REFUND_SUCCEEDED', 'REFUND_FAILED')),
    from_status varchar(20),
    to_status varchar(20) NOT NULL,
    actor_id uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_trade_aftersale_event ON trade_aftersale_event (aftersale_id, occurred_at, id);

-- 商家的默认寄回地址（工作台“售后”里设置；同意退货时不另填就用它，超时自动同意也用它）。一家一条。
CREATE TABLE trade_merchant_return_address (
    organization_id uuid PRIMARY KEY REFERENCES identity_organization(id) ON DELETE RESTRICT,
    contact_name varchar(40) NOT NULL,
    phone varchar(20) NOT NULL,
    address varchar(200) NOT NULL,
    note varchar(200),
    updated_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
