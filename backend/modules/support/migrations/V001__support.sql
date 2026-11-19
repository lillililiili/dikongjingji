-- G1 客服会话和意见反馈（docs/redesign/03-App实现任务计划.md §26，契约 contracts/openapi-support.yaml 1.0.0）。
-- 会话的另一方三种：平台客服、商家、服务商（商家、服务商是身份模块里的服务商类机构）。客户和同一方只有一个会话。
-- 消息只有文字和“关于什么”的卡片（类型 + 稳定 ID + 标题），不存图片、手机号和地址。
-- 表引用 identity_user、identity_organization，排在身份迁移之后（ModuleFlywayConfiguration）。

CREATE TABLE support_conversation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    -- 客户的显示名快照（对方只看到这个，看不到手机号、地址）；客户每发一条消息时刷新。
    customer_name varchar(80) NOT NULL,
    -- PLATFORM 平台客服（没有机构）；MERCHANT 商家；PROVIDER 服务商。
    counterpart_kind varchar(16) NOT NULL CHECK (counterpart_kind IN ('PLATFORM', 'MERCHANT', 'PROVIDER')),
    counterpart_org_id uuid REFERENCES identity_organization(id) ON DELETE RESTRICT,
    -- 对方名称快照（机构名称；平台客服是固定的“平台客服”）。
    counterpart_name varchar(160) NOT NULL,
    -- 消息条数，也是最后一条消息的序号。
    message_count integer NOT NULL DEFAULT 0 CHECK (message_count >= 0),
    last_message_at timestamptz,
    -- 两边各自没读的对方消息条数。
    customer_unread integer NOT NULL DEFAULT 0 CHECK (customer_unread >= 0),
    counterpart_unread integer NOT NULL DEFAULT 0 CHECK (counterpart_unread >= 0),
    customer_read_at timestamptz,
    counterpart_read_at timestamptz,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_support_conversation_counterpart CHECK ((counterpart_kind = 'PLATFORM') = (counterpart_org_id IS NULL)),
    CONSTRAINT ck_support_conversation_last CHECK ((message_count = 0) = (last_message_at IS NULL))
);

-- 客户和同一方只有一个会话：平台客服一个；同一家机构按商家、服务商各一个。
CREATE UNIQUE INDEX uq_support_conversation_platform ON support_conversation (customer_id)
    WHERE counterpart_kind = 'PLATFORM';
CREATE UNIQUE INDEX uq_support_conversation_party ON support_conversation (customer_id, counterpart_kind, counterpart_org_id)
    WHERE counterpart_kind <> 'PLATFORM';
CREATE INDEX idx_support_conversation_customer ON support_conversation (customer_id, last_message_at DESC, id DESC)
    WHERE message_count > 0;
CREATE INDEX idx_support_conversation_organization ON support_conversation (counterpart_org_id, last_message_at DESC, id DESC)
    WHERE message_count > 0;
CREATE INDEX idx_support_conversation_platform ON support_conversation (last_message_at DESC, id DESC)
    WHERE counterpart_kind = 'PLATFORM' AND message_count > 0;

-- 消息，只追加。sender_side 是哪一边发的；sender_id 是发的账号（客户本人，或对方的操作员、平台客服），不对外。
CREATE TABLE support_message (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid NOT NULL REFERENCES support_conversation(id) ON DELETE RESTRICT,
    seq integer NOT NULL CHECK (seq >= 1),
    sender_side varchar(16) NOT NULL CHECK (sender_side IN ('CUSTOMER', 'COUNTERPART')),
    sender_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    kind varchar(8) NOT NULL CHECK (kind IN ('TEXT', 'CARD')),
    text_body varchar(500),
    -- 卡片：关于什么（类型 + 稳定 ID + 标题，副标题可选）。点开时页面自己按 ID 读事实，卡片上的字只是发送时的快照。
    card_type varchar(32) CHECK (card_type IN ('PRODUCT', 'MALL_ORDER', 'MALL_INQUIRY', 'INSPECTION_SERVICE',
                                               'INSPECTION_ORDER', 'PRO_SERVICE', 'ACTIVITY', 'ACTIVITY_ENROLLMENT',
                                               'VENUE', 'VENUE_BOOKING', 'RENTAL_INQUIRY', 'RENTAL_ORDER', 'DEMAND')),
    card_ref_id uuid,
    card_title varchar(60),
    card_subtitle varchar(60),
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (conversation_id, seq),
    UNIQUE (sender_id, idempotency_key),
    CONSTRAINT ck_support_message_body CHECK (
        (kind = 'TEXT' AND text_body IS NOT NULL AND card_type IS NULL AND card_ref_id IS NULL
            AND card_title IS NULL AND card_subtitle IS NULL)
        OR
        (kind = 'CARD' AND text_body IS NULL AND card_type IS NOT NULL AND card_ref_id IS NOT NULL
            AND card_title IS NOT NULL)
    )
);

-- 意见反馈（帮助与反馈页）：记下来，不承诺处理时限，没有处理状态。
CREATE TABLE support_feedback (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    -- FUNCTION 功能问题；DISPLAY 页面显示；CONTENT 内容有误；SUGGESTION 其他建议。
    feedback_type varchar(16) NOT NULL CHECK (feedback_type IN ('FUNCTION', 'DISPLAY', 'CONTENT', 'SUGGESTION')),
    body varchar(200) NOT NULL,
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, idempotency_key)
);

CREATE INDEX idx_support_feedback_created ON support_feedback (created_at DESC, id DESC);
