-- 站内信：平台发给某个账号的一条通知，由产生它的业务在事件发生时写入，之后不补记。
-- identity_user 和 pgcrypto 由身份模块的迁移创建，本模块排在其后（ModuleFlywayConfiguration）。
-- 不存任何业务对象的内容，只存展示用的标题、正文和跳转用的稳定 ID。

CREATE TABLE notification_message (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    -- 目前只有巡检的服务进度；交易、活动、系统通知在各自上线时扩展这个约束。
    category varchar(32) NOT NULL CHECK (category IN ('SERVICE_PROGRESS')),
    event_code varchar(80) NOT NULL,
    title varchar(80) NOT NULL,
    body varchar(300) NOT NULL,
    -- 点开通知去哪：目标类型加稳定 ID，页面自己再向服务端读事实。
    target_type varchar(40) NOT NULL
        CHECK (target_type IN ('INSPECTION_PLAN', 'INSPECTION_FULFILLMENT', 'INSPECTION_DELIVERY')),
    target_id uuid NOT NULL,
    target_sub_id uuid,
    -- 产生方给出的幂等键（例如 inspection-outbox:<事件 ID>），同一事件重放不会出现两条通知。
    source_key varchar(120) NOT NULL UNIQUE,
    occurred_at timestamptz NOT NULL,
    read_at timestamptz,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DELETED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_message_inbox
    ON notification_message (recipient_id, occurred_at DESC, id DESC)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_notification_message_unread
    ON notification_message (recipient_id)
    WHERE status = 'ACTIVE' AND read_at IS NULL;
