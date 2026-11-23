-- S5-9：系统通知、通知偏好和推送（docs/redesign/08-后续开发计划.md §2 S5-9、§5“推送”，契约 contracts/openapi-notification.yaml 1.5.0）。
--
-- 1. 站内信分类加 SYSTEM_NOTICE（系统通知：平台运营发给全体或某个城市的公告），跳转目标加 SYSTEM_NOTICE（ID 是公告）。
--    已有的通知都是之前的六类，仍然满足新的约束。
-- 2. notification_system_notice：公告本身。登记一次，不在发布时给每个账号写一条；账号读自己的站内信时，
--    把发给全体、发给它当前城市的公告补记成它的一条站内信（source_key = system-notice:<公告>:<账号>，只记一次），
--    之后已读、全部已读和别的站内信一样。这样不用读身份模块的账号表，也能发给以后注册的账号。
-- 3. notification_preference：账号的通知开关（设置页“通知”），没有这一行就是全开。开关只管推送，站内信照记。
-- 4. notification_push_device：App 上报的设备推送标识（厂商未定，模拟服务接受任意标识）。
-- 5. notification_push_fake_delivery：只有开发环境的本机模拟推送（FakePushGateway）往这里写，别的环境这张表一直是空的。

ALTER TABLE notification_message DROP CONSTRAINT notification_message_category_check;
ALTER TABLE notification_message ADD CONSTRAINT notification_message_category_check
    CHECK (category IN ('SERVICE_PROGRESS', 'DEMAND_QUOTE', 'CUSTOMER_SERVICE', 'TRADE_LOGISTICS', 'ACTIVITY_REMINDER',
                        'LIVE_REMINDER', 'SYSTEM_NOTICE'));
ALTER TABLE notification_message DROP CONSTRAINT notification_message_target_type_check;
ALTER TABLE notification_message ADD CONSTRAINT notification_message_target_type_check
    CHECK (target_type IN ('INSPECTION_PLAN', 'INSPECTION_FULFILLMENT', 'INSPECTION_DELIVERY', 'DEMAND', 'CONVERSATION',
                           'TRADE_ORDER', 'ACTIVITY_ENROLLMENT', 'ACTIVITY_ORDER', 'VENUE_BOOKING', 'LIVE_ROOM',
                           'SYSTEM_NOTICE'));

CREATE TABLE notification_system_notice (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    title varchar(40) NOT NULL,
    -- 站内信列表里的那一行正文，也是详情页标题下的导语。
    summary varchar(120) NOT NULL,
    -- 详情：{paragraphs, facts, items, footnote, action}，结构和长度由服务校验（SystemNoticeService）。
    content jsonb NOT NULL,
    -- 这份公告说的是哪份协议的更新（协议页“有更新 · 查看本次更新摘要”）；不关协议时为空。
    about_agreement varchar(40)
        CHECK (about_agreement IN ('PRIVACY_POLICY', 'USER_AGREEMENT', 'CHILD_PRIVACY_RULES', 'TRADE_AGREEMENT',
                                   'ENROLLMENT_NOTICE')),
    audience_scope varchar(16) NOT NULL CHECK (audience_scope IN ('ALL', 'CITY')),
    city_code varchar(16),
    published_by uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    published_at timestamptz NOT NULL,
    -- 过了这个时间就不再补记给还没收到的账号；已经收到的不受影响。为空表示一直有效。
    expires_at timestamptz,
    -- 发布接口的幂等键：同一个键重发得到同一份公告。开发种子没有发布人和幂等键。
    publish_key varchar(80) UNIQUE,
    status varchar(16) NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('PUBLISHED', 'WITHDRAWN')),
    withdrawn_at timestamptz,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((audience_scope = 'ALL' AND city_code IS NULL) OR (audience_scope = 'CITY' AND city_code IS NOT NULL)),
    CHECK (expires_at IS NULL OR expires_at > published_at),
    CHECK ((status = 'WITHDRAWN') = (withdrawn_at IS NOT NULL))
);

CREATE INDEX idx_notification_system_notice_live
    ON notification_system_notice (published_at DESC, id DESC)
    WHERE status = 'PUBLISHED';

CREATE INDEX idx_notification_system_notice_agreement
    ON notification_system_notice (about_agreement, published_at DESC)
    WHERE status = 'PUBLISHED' AND about_agreement IS NOT NULL;

CREATE TABLE notification_preference (
    account_id uuid PRIMARY KEY REFERENCES identity_user(id) ON DELETE RESTRICT,
    -- 订单与服务进度的推送：服务进度、需求与报价、客服消息、交易物流。
    order_service_push boolean NOT NULL DEFAULT true,
    -- 活动提醒的推送：活动提醒、直播提醒。
    activity_push boolean NOT NULL DEFAULT true,
    -- 底栏“发现”的内容更新红点（只是 App 显示用的开关，和推送无关）。
    discover_dot boolean NOT NULL DEFAULT true,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE notification_push_device (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    platform varchar(16) NOT NULL CHECK (platform IN ('ANDROID', 'IOS')),
    -- 推送服务给这台设备的标识（uni.getPushClientId 等）。同一台设备换账号登录时，归到最后上报的账号。
    push_client_id varchar(200) NOT NULL,
    -- 上报时 App 的当前城市，只用来给“按城市”的系统通知挑设备。
    city_code varchar(16),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    last_reported_at timestamptz NOT NULL,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (platform, push_client_id)
);

CREATE INDEX idx_notification_push_device_account
    ON notification_push_device (account_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_notification_push_device_city
    ON notification_push_device (city_code)
    WHERE status = 'ACTIVE';

CREATE TABLE notification_push_fake_delivery (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id uuid NOT NULL REFERENCES notification_push_device(id) ON DELETE CASCADE,
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE CASCADE,
    category varchar(32) NOT NULL,
    title varchar(80) NOT NULL,
    body varchar(300) NOT NULL,
    target_type varchar(40) NOT NULL,
    target_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_push_fake_delivery_account
    ON notification_push_fake_delivery (account_id, created_at DESC);
