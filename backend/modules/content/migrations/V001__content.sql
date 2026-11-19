-- H1 发现页图文内容与专题（docs/redesign/03-App实现任务计划.md §27，契约 contracts/openapi-content.yaml 1.0.0）。
-- 公开只读；没有运营端，内容和专题只能由开发种子（以后由运营端）录入。
-- “内容中提到”的对象（巡检服务、专业服务、商品、活动、场馆）和专题挂的活动、场馆只存类型 + 稳定 ID：
-- 页面按 ID 到各自的模块读真实数据，本模块不引用它们的表。
-- 表引用 identity_organization，排在身份迁移之后（ModuleFlywayConfiguration）。

-- 图文内容。CASE 案例（服务商、商家发布）；KNOWLEDGE 知识（平台或机构发布）。
CREATE TABLE content_article (
    id uuid PRIMARY KEY,
    kind varchar(16) NOT NULL CHECK (kind IN ('CASE', 'KNOWLEDGE')),
    title varchar(60) NOT NULL,
    -- 作者：PROVIDER 服务商、MERCHANT 商家（带机构 ID，“主页”按它走）；PLATFORM 平台或城市运营（没有机构）。
    author_kind varchar(16) NOT NULL CHECK (author_kind IN ('PROVIDER', 'MERCHANT', 'PLATFORM')),
    author_org_id uuid REFERENCES identity_organization(id) ON DELETE RESTRICT,
    -- 作者名称快照（发布时的机构名称，或平台的署名如“东营城市运营中心”）。
    author_name varchar(160) NOT NULL,
    -- 广告：商家发布的内容一律是广告。
    sponsored boolean NOT NULL DEFAULT false,
    -- 城市；为空表示不分城市（只有知识可以不分城市）。
    city_code varchar(16),
    cover_url varchar(500),
    -- 示例数据（文字沿用原型），页面标“示例”。
    sample boolean NOT NULL DEFAULT false,
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'OFFLINE')),
    published_at timestamptz,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_content_article_author CHECK ((author_kind = 'PLATFORM') = (author_org_id IS NULL)),
    CONSTRAINT ck_content_article_case_author CHECK (kind <> 'CASE' OR author_kind IN ('PROVIDER', 'MERCHANT')),
    CONSTRAINT ck_content_article_sponsored CHECK (author_kind <> 'MERCHANT' OR sponsored),
    CONSTRAINT ck_content_article_city CHECK (kind = 'KNOWLEDGE' OR city_code IS NOT NULL),
    CONSTRAINT ck_content_article_published CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);

CREATE INDEX idx_content_article_feed ON content_article (published_at DESC, id DESC) WHERE status = 'PUBLISHED';
CREATE INDEX idx_content_article_city ON content_article (city_code, kind) WHERE status = 'PUBLISHED';

-- 正文：按顺序的段落块。PARAGRAPH 段落、HEADING 小标题、TIP 提示、NOTICE 文末说明带 text；
-- FACTS 要点表（条目：标题 = 名称，正文 = 值）、STEPS 编号步骤、LIST 条目列表带条目，没有 text。
CREATE TABLE content_article_block (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    article_id uuid NOT NULL REFERENCES content_article(id) ON DELETE CASCADE,
    sort_order integer NOT NULL,
    block_type varchar(16) NOT NULL
        CHECK (block_type IN ('PARAGRAPH', 'HEADING', 'FACTS', 'STEPS', 'LIST', 'TIP', 'NOTICE')),
    text varchar(500),
    UNIQUE (article_id, sort_order),
    CONSTRAINT ck_content_article_block_text CHECK ((block_type IN ('FACTS', 'STEPS', 'LIST')) = (text IS NULL))
);

CREATE TABLE content_article_block_item (
    block_id uuid NOT NULL REFERENCES content_article_block(id) ON DELETE CASCADE,
    sort_order integer NOT NULL,
    title varchar(60) NOT NULL,
    text varchar(200),
    PRIMARY KEY (block_id, sort_order)
);

-- “内容中提到”：类型 + 稳定 ID；title 是录入时的名称快照，页面按 ID 读真实的名称、价格和状态。
CREATE TABLE content_article_ref (
    article_id uuid NOT NULL REFERENCES content_article(id) ON DELETE CASCADE,
    sort_order integer NOT NULL,
    ref_type varchar(24) NOT NULL
        CHECK (ref_type IN ('INSPECTION_SERVICE', 'PRO_SERVICE', 'PRODUCT', 'ACTIVITY', 'VENUE')),
    ref_id uuid NOT NULL,
    title varchar(60) NOT NULL,
    PRIMARY KEY (article_id, sort_order),
    UNIQUE (article_id, ref_type, ref_id)
);

-- 专题：一个城市一段时间的合集（发现页推荐频道顶部的专题卡）。
CREATE TABLE content_topic (
    id uuid PRIMARY KEY,
    title varchar(40) NOT NULL,
    subtitle varchar(60),
    publisher_kind varchar(16) NOT NULL CHECK (publisher_kind IN ('PROVIDER', 'MERCHANT', 'PLATFORM')),
    publisher_org_id uuid REFERENCES identity_organization(id) ON DELETE RESTRICT,
    publisher_name varchar(160) NOT NULL,
    city_code varchar(16) NOT NULL,
    -- 起止日期（北京时间，含两端）。
    starts_on date NOT NULL,
    ends_on date NOT NULL,
    cover_url varchar(500),
    sample boolean NOT NULL DEFAULT false,
    status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'OFFLINE')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_content_topic_publisher CHECK ((publisher_kind = 'PLATFORM') = (publisher_org_id IS NULL)),
    CONSTRAINT ck_content_topic_dates CHECK (ends_on >= starts_on)
);

CREATE INDEX idx_content_topic_city ON content_topic (city_code, starts_on, ends_on) WHERE status = 'PUBLISHED';

-- 专题挂的东西：活动、场馆（ID，页面到赛事研学、体验模块读真实数据）、图文（本模块的内容）。
CREATE TABLE content_topic_item (
    topic_id uuid NOT NULL REFERENCES content_topic(id) ON DELETE CASCADE,
    sort_order integer NOT NULL,
    item_type varchar(16) NOT NULL CHECK (item_type IN ('ACTIVITY', 'VENUE', 'ARTICLE')),
    item_id uuid NOT NULL,
    PRIMARY KEY (topic_id, sort_order),
    UNIQUE (topic_id, item_type, item_id)
);
