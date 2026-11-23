-- S5-5 课程（docs/redesign/08-后续开发计划.md §2 S5-5、§5 D15、D12；契约 contracts/openapi-course.yaml 1.0.0、
-- contracts/openapi-ops-course.yaml 1.0.0；说明见 docs/redesign/09-课程实现说明.md）。
--
-- 一期只卖线上录播课（ONLINE）：章节、课时、时长、讲师、适合年龄、价格、试听课时；付款经统一交易模块（来源 COURSE，
-- 钱进开课机构自己的子商户号），付款成功即开通，学习进度按课时记。线下班（OFFLINE）只是商城“课程”页里的一张卡，
-- 指向赛事研学模块的一个活动（只存活动 ID），报名、收钱都在活动那边；活动不存在、没上线、不在当前城市时卡片不显示。
-- 商城商品附带的数字课程：course_bundle 记“哪个商品附带这门课”（只存商品 ID，不引用商城的表），商城订单完成后
-- 交易模块经端口告诉课程模块，按订单开通（source BUNDLE）。

CREATE TABLE course (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- 开课机构：线上课的收款方、讲师所属；线下班是挂靠的活动的主办（只做运营端显示）。
    organization_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    delivery_mode varchar(16) NOT NULL CHECK (delivery_mode IN ('ONLINE', 'OFFLINE')),
    topic varchar(16) NOT NULL CHECK (topic IN ('DRONE', 'ROBOT', 'PROGRAMMING', 'CERTIFICATE')),
    title varchar(60) NOT NULL,
    subtitle varchar(120),
    cover_url varchar(500) CHECK (cover_url LIKE 'https://%'),
    -- 图集（不含封面）：受控 HTTPS 地址的数组，≤ 9 张。
    gallery_urls jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(gallery_urls) = 'array'),
    -- 线上课：单独出售的价格（分）；为空表示不单独出售（只随商品附带）。
    price_amount_minor bigint CHECK (price_amount_minor BETWEEN 1 AND 10000000),
    -- 开通后多少天内能学；为空表示长期有效。
    validity_days integer CHECK (validity_days BETWEEN 1 AND 3650),
    age_note varchar(30),
    instructor_name varchar(30),
    instructor_title varchar(40),
    -- “课程包含”：[{"name": "录播课程", "quantity": 12}]，≤ 6 项；“参数”的补充行：[{"label": "证书", "value": "…"}]，≤ 6 项。
    includes jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(includes) = 'array'),
    extra_specs jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(extra_specs) = 'array'),
    -- 图文介绍：段落文字，≤ 20 段。
    intro jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(intro) = 'array'),
    -- 体验中心里这门课的“课程试听”入口（体验模块的项目 ID，只存 ID）。
    experience_item_id uuid UNIQUE,
    -- 线下班：挂靠的活动（赛事研学模块，只存 ID）和卡片上的“8 次课”“3 天”“按班期”。
    activity_id uuid,
    schedule_label varchar(12),
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'LISTED', 'UNLISTED')),
    first_listed_at timestamptz,
    version integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT course_offline_points_at_activity CHECK (
        (delivery_mode = 'OFFLINE') = (activity_id IS NOT NULL)
    ),
    CONSTRAINT course_offline_sells_nothing CHECK (
        delivery_mode = 'ONLINE' OR (price_amount_minor IS NULL AND validity_days IS NULL AND experience_item_id IS NULL)
    )
);

CREATE INDEX idx_course_listed ON course (status, delivery_mode, topic, sort_order DESC, id);

CREATE TABLE course_chapter (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    course_id uuid NOT NULL REFERENCES course(id) ON DELETE CASCADE,
    title varchar(40) NOT NULL,
    sort_order integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (course_id, sort_order)
);

-- 课时：一节录播视频。video_url 只放受控 HTTPS 地址（运营端素材上传给的或 CDN 地址）；开发环境没有视频存储，为空，
-- 预览构建播放由画板图片合成的示例视频。trial 为 true 的课时不买也能看（试听），看试听不记进度。
CREATE TABLE course_lesson (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    course_id uuid NOT NULL REFERENCES course(id) ON DELETE CASCADE,
    chapter_id uuid NOT NULL REFERENCES course_chapter(id) ON DELETE CASCADE,
    title varchar(40) NOT NULL,
    duration_seconds integer NOT NULL CHECK (duration_seconds BETWEEN 1 AND 14400),
    trial boolean NOT NULL DEFAULT false,
    video_url varchar(500) CHECK (video_url LIKE 'https://%'),
    -- 全课的顺序（第 1 课、第 2 课……），跨章连续。
    sort_order integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (course_id, sort_order)
);

CREATE INDEX idx_course_lesson_chapter ON course_lesson (chapter_id, sort_order);

-- 附带这门课的商城商品（只存商品 ID）：这些商品的商城订单完成后自动开通。
CREATE TABLE course_bundle (
    course_id uuid NOT NULL REFERENCES course(id) ON DELETE CASCADE,
    product_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (course_id, product_id)
);

CREATE INDEX idx_course_bundle_product ON course_bundle (product_id);

-- 开通记录（“我的课程”的一行）。
--   PURCHASE：自己买的。PENDING_PAYMENT → 付款到账 ACTIVE；没付款就关闭 CLOSED；7 天内没开始学非试听课时可退 → REFUNDED。
--   BUNDLE：随商城订单开通，直接 ACTIVE；退款跟着商品订单走，这里没有退款。
--   EXPIRED：过了有效期之后又重新开通了同一门课，旧的那条改成 EXPIRED（平时过期按时间算，不落库）。
-- 同一个账号同一门课同时只有一条 ACTIVE；买的时候已有待付款的直接返回那一条。
CREATE TABLE course_enrollment (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    course_id uuid NOT NULL REFERENCES course(id) ON DELETE RESTRICT,
    source varchar(16) NOT NULL CHECK (source IN ('PURCHASE', 'BUNDLE')),
    status varchar(16) NOT NULL CHECK (status IN ('PENDING_PAYMENT', 'ACTIVE', 'CLOSED', 'REFUNDED', 'EXPIRED')),
    amount_minor bigint NOT NULL DEFAULT 0 CHECK (amount_minor >= 0),
    trade_order_id uuid UNIQUE,
    pay_before timestamptz,
    paid_at timestamptz,
    activated_at timestamptz,
    expires_at timestamptz,
    -- 第一次开始学一节非试听课时的时间：有了它就不能在线退款。
    started_at timestamptz,
    refund_id uuid,
    refund_amount_minor bigint,
    refunded_at timestamptz,
    -- BUNDLE：来源商城订单的快照（订单 ID 和编号、商品名、规格名）。
    bundle_order_id uuid,
    bundle_order_no varchar(40),
    bundle_product_name varchar(160),
    bundle_sku_name varchar(80),
    idempotency_key uuid,
    request_hash char(64),
    version integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, idempotency_key),
    UNIQUE (bundle_order_id, course_id),
    CONSTRAINT course_enrollment_purchase_has_order CHECK (
        (source = 'PURCHASE') = (trade_order_id IS NOT NULL)
    ),
    CONSTRAINT course_enrollment_bundle_has_source CHECK (
        (source = 'BUNDLE') = (bundle_order_id IS NOT NULL)
    ),
    CONSTRAINT course_enrollment_active_is_activated CHECK (
        status NOT IN ('ACTIVE', 'REFUNDED', 'EXPIRED') OR activated_at IS NOT NULL
    )
);

CREATE UNIQUE INDEX uq_course_enrollment_active ON course_enrollment (account_id, course_id) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX uq_course_enrollment_pending ON course_enrollment (account_id, course_id)
    WHERE status = 'PENDING_PAYMENT';
CREATE INDEX idx_course_enrollment_account ON course_enrollment (account_id, status, activated_at DESC, id);

-- 学习进度：一节课时一行。started_at 是第一次拿播放地址的时间，completed_at 是第一次播完的时间（重看不改）。
CREATE TABLE course_lesson_progress (
    enrollment_id uuid NOT NULL REFERENCES course_enrollment(id) ON DELETE CASCADE,
    lesson_id uuid NOT NULL REFERENCES course_lesson(id) ON DELETE RESTRICT,
    started_at timestamptz NOT NULL,
    completed_at timestamptz,
    last_played_at timestamptz NOT NULL,
    PRIMARY KEY (enrollment_id, lesson_id)
);

-- 开通记录的事件（审计），只追加。
CREATE TABLE course_enrollment_event (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    enrollment_id uuid NOT NULL REFERENCES course_enrollment(id) ON DELETE CASCADE,
    event varchar(24) NOT NULL CHECK (event IN (
        'ORDERED', 'PAYMENT_CONFIRMED', 'PAYMENT_RESTORED', 'PAYMENT_CLOSED', 'BUNDLED', 'STARTED',
        'REFUND_REQUESTED'
    )),
    actor_id uuid,
    occurred_at timestamptz NOT NULL
);

CREATE INDEX idx_course_enrollment_event ON course_enrollment_event (enrollment_id, occurred_at);

-- 运营改动记录（OpsChange），只追加。operator_name 是操作时运营账号的显示名快照。
CREATE TABLE course_ops_change (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    course_id uuid NOT NULL REFERENCES course(id) ON DELETE RESTRICT,
    action varchar(16) NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'OUTLINE', 'LIST', 'UNLIST')),
    summary varchar(200) NOT NULL,
    operator_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    operator_name varchar(80) NOT NULL,
    changed_at timestamptz NOT NULL
);

CREATE INDEX idx_course_ops_change_course ON course_ops_change (course_id, changed_at DESC, id DESC);
