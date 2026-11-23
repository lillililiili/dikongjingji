-- S3-1 运营端“发现内容”（docs/redesign/08-后续开发计划.md §2，契约 contracts/openapi-ops-content.yaml 1.0.0、
-- contracts/openapi-content.yaml 1.3.0）：正文配图块、专题里的商家内容、运营改动记录。
-- 已发布的内容和专题不受影响：新列都可以为空，旧块和旧专题条目照旧满足新的约束。

-- 1. 正文配图块 IMAGE（S4-4，画板 ContentArticle“怎么巡”后面那张图）：图片地址、替代文字、图注，没有 text 和条目。
--    图片地址只放受控 HTTPS 地址；开发种子没有授权图片时为空（预览构建用画板图片顶替），运营端上线前必须填。
--    V001 里 block_type 的 CHECK 是列约束，PostgreSQL 给它的名字是 content_article_block_block_type_check。
ALTER TABLE content_article_block DROP CONSTRAINT content_article_block_block_type_check;
ALTER TABLE content_article_block
    ADD CONSTRAINT ck_content_article_block_type
        CHECK (block_type IN ('PARAGRAPH', 'HEADING', 'FACTS', 'STEPS', 'LIST', 'TIP', 'NOTICE', 'IMAGE'));

ALTER TABLE content_article_block DROP CONSTRAINT ck_content_article_block_text;
ALTER TABLE content_article_block
    ADD CONSTRAINT ck_content_article_block_text
        CHECK ((block_type IN ('FACTS', 'STEPS', 'LIST', 'IMAGE')) = (text IS NULL));

ALTER TABLE content_article_block
    ADD COLUMN image_url varchar(500) CHECK (image_url IS NULL OR image_url LIKE 'https://%'),
    ADD COLUMN image_alt varchar(60),
    ADD COLUMN image_caption varchar(60);

ALTER TABLE content_article_block
    ADD CONSTRAINT ck_content_article_block_image_alt CHECK ((block_type = 'IMAGE') = (image_alt IS NOT NULL)),
    ADD CONSTRAINT ck_content_article_block_image_only
        CHECK (block_type = 'IMAGE' OR (image_url IS NULL AND image_caption IS NULL));

-- 2. 专题里的商家内容（S4-4，画板 Topic“商家内容”）：挂商家的商品，页面按商品 ID 到商城读真实的名称、价格和在售商家，
--    标“广告”。商品属于商城模块：只存 ID，不加外键；发布商家存机构 ID 和名称快照。
--    V001 里 item_type 的 CHECK 是列约束，名字是 content_topic_item_item_type_check。
ALTER TABLE content_topic_item DROP CONSTRAINT content_topic_item_item_type_check;
ALTER TABLE content_topic_item
    ADD CONSTRAINT ck_content_topic_item_type CHECK (item_type IN ('ACTIVITY', 'VENUE', 'ARTICLE', 'PRODUCT'));

ALTER TABLE content_topic_item
    ADD COLUMN merchant_org_id uuid REFERENCES identity_organization(id) ON DELETE RESTRICT,
    ADD COLUMN merchant_name varchar(160);

ALTER TABLE content_topic_item
    ADD CONSTRAINT ck_content_topic_item_merchant
        CHECK ((item_type = 'PRODUCT') = (merchant_org_id IS NOT NULL AND merchant_name IS NOT NULL));

-- 3. 运营改动记录（留痕），只追加。subject_type：ARTICLE 图文、VIDEO 视频、TOPIC 专题；
--    action：CREATE、UPDATE、PUBLISH、OFFLINE。operator_name 是操作时运营账号的显示名快照。
CREATE TABLE content_ops_change (
    id uuid PRIMARY KEY,
    subject_type varchar(16) NOT NULL CHECK (subject_type IN ('ARTICLE', 'VIDEO', 'TOPIC')),
    subject_id uuid NOT NULL,
    action varchar(16) NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'PUBLISH', 'OFFLINE')),
    summary varchar(200) NOT NULL,
    operator_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    operator_name varchar(80) NOT NULL,
    changed_at timestamptz NOT NULL
);

CREATE INDEX idx_content_ops_change_subject ON content_ops_change (subject_type, subject_id, changed_at DESC, id DESC);

-- 运营端列表：按最近修改排。
CREATE INDEX idx_content_article_ops ON content_article (kind, updated_at DESC, id DESC);
CREATE INDEX idx_content_topic_ops ON content_topic (updated_at DESC, id DESC);
