-- F2：航拍、测绘、设备维护、技术支持四类专业服务的目录和详情（docs/redesign/03-App实现任务计划.md §23，
-- 契约 contracts/openapi-proservice.yaml 1.0.0）。只读：看服务、看服务商，下单走“发布需求”（F1），这里不建订单。
-- identity_organization 由身份模块创建，本模块排在巡检之后（ModuleFlywayConfiguration）。
-- 服务商资料（名称以外的认证标签、简介）属于巡检模块，由启动模块通过端口读，这里不引用它的表。
-- 金额一律存分，只做展示和排序。

CREATE TABLE pro_service (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    type varchar(16) NOT NULL CHECK (type IN ('AERIAL', 'SURVEY', 'MAINTENANCE', 'TECH_SUPPORT')),
    -- 场景分类：卡片和详情没有授权照片时，App 按它画场景图标。
    category_code varchar(16) NOT NULL
        CHECK (category_code IN ('ENGINEERING', 'ENERGY', 'TOURISM', 'EVENT', 'MODELING', 'PARK', 'TERRAIN',
                                 'CORRIDOR', 'GENERAL', 'DRONE', 'ROBOT', 'ROBOT_DOG', 'FLIGHT', 'SOFTWARE')),
    title varchar(40) NOT NULL,
    summary varchar(80) NOT NULL,
    -- PRICE_FROM 起步价：金额 + 单位（如“/次起”）+ 一句起步价含什么；PROJECT 按项目报价，没有金额和单位。
    price_mode varchar(12) NOT NULL CHECK (price_mode IN ('PRICE_FROM', 'PROJECT')),
    price_amount_minor bigint CHECK (price_amount_minor > 0),
    price_unit varchar(16),
    price_note varchar(40),
    -- 授权照片的地址；没有时为空，不放示意照片。
    cover_url varchar(500) CHECK (cover_url IS NULL OR cover_url LIKE 'https://%'),
    -- 示例数据：金额和服务商沿用原型，页面标“示例”。只有开发种子写 true。
    sample boolean NOT NULL DEFAULT false,
    -- 综合排序的顺序，小的在前。
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'PUBLISHED' CHECK (status IN ('PUBLISHED', 'UNPUBLISHED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_pro_service_price CHECK (
        (price_mode = 'PRICE_FROM' AND price_amount_minor IS NOT NULL AND price_unit IS NOT NULL
            AND price_note IS NOT NULL)
        OR (price_mode = 'PROJECT' AND price_amount_minor IS NULL AND price_unit IS NULL)
    )
);

CREATE INDEX idx_pro_service_type ON pro_service (type, status, sort_order, id);
CREATE INDEX idx_pro_service_provider ON pro_service (provider_id, status);

-- 上架的城市（平台城市目录的城市编码）。
CREATE TABLE pro_service_city (
    service_id uuid NOT NULL REFERENCES pro_service(id) ON DELETE CASCADE,
    city_code varchar(16) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (service_id, city_code)
);

CREATE INDEX idx_pro_service_city ON pro_service_city (city_code, status, service_id);

-- 服务的展示内容，一行一项，按 kind 分组、sort_order 排序：
-- TAG 卡片标签；FACET 筛选项（code，见下）；CAPABILITY 能力标签；PRICE_ROW 价格与取消（label + detail）；
-- SCOPE 服务内容（拍摄内容 / 作业范围 / 适用设备 / 支持内容）；OUTPUT 成果格式（label + detail）；
-- SERVICE_MODE 服务方式（label + detail）；TERM 说明行（精度说明 / 保修与收费边界 / 响应说明，label + detail）；
-- STEP 服务流程；GUARANTEE 服务保障。
CREATE TABLE pro_service_item (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id uuid NOT NULL REFERENCES pro_service(id) ON DELETE CASCADE,
    kind varchar(16) NOT NULL
        CHECK (kind IN ('TAG', 'FACET', 'CAPABILITY', 'PRICE_ROW', 'SCOPE', 'OUTPUT', 'SERVICE_MODE', 'TERM',
                        'STEP', 'GUARANTEE')),
    -- 只有 FACET 有编码：成果类型（航拍、测绘）或服务方式（设备维护、技术支持），目录按它筛选。
    code varchar(16)
        CHECK (code IN ('VIDEO_FOOTAGE', 'PHOTOS', 'ORTHOPHOTO', 'MODEL_3D', 'ON_SITE', 'MAIL_IN', 'REMOTE',
                        'STATIONED')),
    label varchar(40) NOT NULL,
    detail varchar(120),
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_pro_service_item_code CHECK ((kind = 'FACET') = (code IS NOT NULL)),
    CONSTRAINT ck_pro_service_item_detail CHECK (
        (kind IN ('PRICE_ROW', 'OUTPUT', 'SERVICE_MODE', 'TERM')) = (detail IS NOT NULL)
    )
);

CREATE INDEX idx_pro_service_item ON pro_service_item (service_id, kind, sort_order, id);
CREATE UNIQUE INDEX uq_pro_service_item_facet ON pro_service_item (service_id, code) WHERE kind = 'FACET';
