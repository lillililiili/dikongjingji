-- M20 平台内直播的观看端（docs/redesign/07-视频与直播实现计划.md §3、§4，契约 contracts/openapi-live.yaml 1.0.0）。
-- 推流与播放接第三方直播服务（LiveStreamProvider 端口，服务商未定）；开播、断流、结束以服务商回调为准。
-- 讲解项只存类型 + 稳定 ID + 主办方写的标题快照：App 按 ID 到各自的模块读真实数据，本模块不引用它们的表。
-- 表引用 identity_user、identity_organization，排在身份迁移之后（ModuleFlywayConfiguration）。

-- 直播间。没有草稿：建了就是预告。
CREATE TABLE live_room (
    id uuid PRIMARY KEY,
    host_org_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    -- 主办机构名称快照（建直播间时）。
    host_name varchar(80) NOT NULL,
    title varchar(30) NOT NULL,
    subtitle varchar(40),
    cover_url varchar(500) CHECK (cover_url IS NULL OR cover_url LIKE 'https://%'),
    -- 为空表示全国可见。
    city_code varchar(16),
    scheduled_at timestamptz NOT NULL,
    speaker_name varchar(20),
    speaker_title varchar(30),
    -- “这场讲什么”：0–5 条，每条一行（换行分隔），每条不超过 40 字。
    topics varchar(210),
    status varchar(16) NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'LIVE', 'ENDED')),
    started_at timestamptz,
    ended_at timestamptz,
    -- 谁结束的：STREAM 服务商回调；PLATFORM 平台客服强制结束。
    end_reason varchar(16) CHECK (end_reason IN ('STREAM', 'PLATFORM')),
    -- 直播中服务商报了断流，还没恢复。
    stream_interrupted boolean NOT NULL DEFAULT false,
    -- 服务商的流：流 ID（回调按它找直播间）、推流地址（只给主办方）、播放地址（只在直播中给观众）。
    stream_id varchar(80) NOT NULL UNIQUE,
    push_url varchar(500) NOT NULL,
    play_url varchar(500) NOT NULL,
    -- 最后处理的那条回调的发生时间；比它旧的回调不处理。
    last_event_at timestamptz,
    replay_url varchar(500) CHECK (replay_url IS NULL OR replay_url LIKE 'https://%'),
    replay_platform_name varchar(20),
    current_item_seq integer,
    -- 观看人数：直播中进过的不同设备数，累计不减。点赞数累计不减。弹幕条数也是最新弹幕的序号。
    viewer_count bigint NOT NULL DEFAULT 0 CHECK (viewer_count >= 0),
    like_count bigint NOT NULL DEFAULT 0 CHECK (like_count >= 0),
    comment_count bigint NOT NULL DEFAULT 0 CHECK (comment_count >= 0),
    -- 主办方建直播间的幂等键（同一个机构里唯一）。开发种子建的没有。
    idempotency_key uuid,
    request_hash char(64),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (host_org_id, idempotency_key),
    CONSTRAINT ck_live_room_started CHECK (status = 'SCHEDULED' OR status = 'ENDED' OR started_at IS NOT NULL),
    CONSTRAINT ck_live_room_ended CHECK ((status = 'ENDED') = (ended_at IS NOT NULL)),
    CONSTRAINT ck_live_room_end_reason CHECK ((status = 'ENDED') = (end_reason IS NOT NULL)),
    CONSTRAINT ck_live_room_interrupted CHECK (status = 'LIVE' OR NOT stream_interrupted),
    CONSTRAINT ck_live_room_replay CHECK ((replay_url IS NULL) = (replay_platform_name IS NULL)),
    CONSTRAINT ck_live_room_key CHECK ((idempotency_key IS NULL) = (request_hash IS NULL))
);

CREATE INDEX idx_live_room_list ON live_room (status, city_code);
CREATE INDEX idx_live_room_host ON live_room (host_org_id, created_at DESC, id DESC);

-- 讲解清单：序号从 1 起，同一个对象在一场里只出现一次。
CREATE TABLE live_room_item (
    room_id uuid NOT NULL REFERENCES live_room(id) ON DELETE CASCADE,
    seq integer NOT NULL CHECK (seq BETWEEN 1 AND 10),
    item_type varchar(32) NOT NULL CHECK (item_type IN ('PRODUCT', 'PRO_SERVICE', 'INSPECTION_SERVICE', 'ACTIVITY', 'VENUE')),
    ref_id uuid NOT NULL,
    title varchar(40) NOT NULL,
    PRIMARY KEY (room_id, seq),
    UNIQUE (room_id, item_type, ref_id)
);

-- 进过直播间的设备（按直播间去重，观看人数的口径）。viewer_key 是 App 在本机生成的随机标识。
CREATE TABLE live_room_viewer (
    room_id uuid NOT NULL REFERENCES live_room(id) ON DELETE CASCADE,
    viewer_key uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, viewer_key)
);

-- 开播提醒。开播时把还没通知过的标上通知时间，各记一条站内信；取消就删掉。
CREATE TABLE live_reminder (
    room_id uuid NOT NULL REFERENCES live_room(id) ON DELETE CASCADE,
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    notified_at timestamptz,
    PRIMARY KEY (room_id, account_id)
);

-- 关注主播机构（本模块自己存；以后有统一的关注再合并）。
CREATE TABLE live_host_follow (
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE CASCADE,
    host_org_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, host_org_id)
);

-- 点赞按 10 秒一个时间窗合并：同一账号在同一直播间每个时间窗最多记 30 次。
CREATE TABLE live_like_window (
    room_id uuid NOT NULL REFERENCES live_room(id) ON DELETE CASCADE,
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE CASCADE,
    window_start timestamptz NOT NULL,
    like_count integer NOT NULL DEFAULT 0 CHECK (like_count BETWEEN 0 AND 30),
    PRIMARY KEY (room_id, account_id, window_start)
);

-- 弹幕，只追加；隐藏只记时间和是谁隐藏的，不删。
CREATE TABLE live_comment (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id uuid NOT NULL REFERENCES live_room(id) ON DELETE CASCADE,
    seq bigint NOT NULL CHECK (seq >= 1),
    -- VIEWER 观众；HOST 主播（主办机构的操作员）。
    side varchar(8) NOT NULL CHECK (side IN ('VIEWER', 'HOST')),
    author_id uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    -- 显示的作者名：观众是显示名的第一个字加“**”，主播是机构名。
    author_name varchar(80) NOT NULL,
    body varchar(50) NOT NULL,
    hidden_at timestamptz,
    -- HOST 主办方；PLATFORM 平台客服。
    hidden_by varchar(16) CHECK (hidden_by IN ('HOST', 'PLATFORM')),
    idempotency_key uuid,
    request_hash char(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (room_id, seq),
    UNIQUE (author_id, idempotency_key),
    CONSTRAINT ck_live_comment_hidden CHECK ((hidden_at IS NULL) = (hidden_by IS NULL)),
    CONSTRAINT ck_live_comment_key CHECK ((idempotency_key IS NULL) = (request_hash IS NULL)),
    -- 开发种子里的弹幕没有作者账号；观众经接口发的一定有。
    CONSTRAINT ck_live_comment_author CHECK (author_id IS NOT NULL OR idempotency_key IS NULL)
);

CREATE INDEX idx_live_comment_author ON live_comment (author_id, created_at DESC);

-- 处理过的服务商回调（按事件 ID 去重）。
CREATE TABLE live_stream_event (
    event_id varchar(80) PRIMARY KEY,
    room_id uuid NOT NULL REFERENCES live_room(id) ON DELETE CASCADE,
    event_type varchar(16) NOT NULL CHECK (event_type IN ('STARTED', 'INTERRUPTED', 'ENDED')),
    occurred_at timestamptz NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now()
);
