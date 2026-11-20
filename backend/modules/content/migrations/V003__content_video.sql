-- 发现页视频（docs/redesign/07-视频与直播实现计划.md §2、§4，契约 contracts/openapi-content.yaml 1.2.0）。
-- 视频是内容的一类（kind = VIDEO），作者、广告、示例、城市、发布的规则和图文一样（视频必须有城市）；
-- “内容中提到”沿用 content_article_ref，第一项是视频挂的入口。点赞、关注、评论、举报是本模块自己的表；
-- 举报同时记进客服反馈（经端口，不引用客服模块的表）。

-- V001 里 kind 的 CHECK 是列约束，PostgreSQL 给它的名字是 content_article_kind_check。
ALTER TABLE content_article DROP CONSTRAINT content_article_kind_check;
ALTER TABLE content_article
    ADD CONSTRAINT ck_content_article_kind CHECK (kind IN ('CASE', 'KNOWLEDGE', 'VIDEO'));

-- 视频字段：时长（秒）、画面方向、播放地址（只放受控 HTTPS 地址；开发环境为空）、一句话说明、话题（空格分隔，不带 #）、
-- 运营排序（大的在前，再按发布时间）。图文这些列都为空、排序为 0。
ALTER TABLE content_article
    ADD COLUMN video_duration_seconds integer CHECK (video_duration_seconds BETWEEN 1 AND 3600),
    ADD COLUMN video_orientation varchar(16) CHECK (video_orientation IN ('PORTRAIT', 'LANDSCAPE')),
    ADD COLUMN video_url varchar(500),
    ADD COLUMN video_caption varchar(200),
    ADD COLUMN video_tags varchar(120),
    ADD COLUMN video_rank integer NOT NULL DEFAULT 0;

ALTER TABLE content_article
    ADD CONSTRAINT ck_content_article_video
        CHECK ((kind = 'VIDEO') = (video_duration_seconds IS NOT NULL AND video_orientation IS NOT NULL)),
    ADD CONSTRAINT ck_content_article_video_fields
        CHECK (kind = 'VIDEO' OR (video_url IS NULL AND video_caption IS NULL AND video_tags IS NULL AND video_rank = 0));

CREATE INDEX idx_content_video_stream ON content_article (video_rank DESC, published_at DESC, id DESC)
    WHERE status = 'PUBLISHED' AND kind = 'VIDEO';

-- 点赞：一个账号对一条视频一次。
CREATE TABLE content_video_like (
    video_id uuid NOT NULL REFERENCES content_article(id) ON DELETE CASCADE,
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (video_id, account_id)
);

CREATE INDEX idx_content_video_like_account ON content_video_like (account_id);

-- 关注发布机构（服务商、商家）。
CREATE TABLE content_follow (
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, organization_id)
);

CREATE INDEX idx_content_follow_organization ON content_follow (organization_id);

-- 评论：过关键词过滤后直接显示；平台客服或发布机构的操作员可以隐藏（HIDDEN 后谁都看不到，也不算数）。
-- display_name 是发评论时的显示名打码，发布机构的操作员发的是机构名（by_author）。
CREATE TABLE content_video_comment (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    video_id uuid NOT NULL REFERENCES content_article(id) ON DELETE CASCADE,
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    display_name varchar(160) NOT NULL,
    by_author boolean NOT NULL DEFAULT false,
    body varchar(200) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'VISIBLE' CHECK (status IN ('VISIBLE', 'HIDDEN')),
    hidden_by uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    hidden_at timestamptz,
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, idempotency_key),
    CONSTRAINT ck_content_video_comment_hidden
        CHECK ((status = 'HIDDEN') = (hidden_at IS NOT NULL AND hidden_by IS NOT NULL))
);

CREATE INDEX idx_content_video_comment_video ON content_video_comment (video_id, created_at DESC, id DESC)
    WHERE status = 'VISIBLE';
CREATE INDEX idx_content_video_comment_account ON content_video_comment (account_id, created_at DESC);

-- 举报：同一账号对同一条评论只记一次；feedback_id 是记进客服反馈的那一条（客服模块的 ID，不加外键）。
CREATE TABLE content_comment_report (
    comment_id uuid NOT NULL REFERENCES content_video_comment(id) ON DELETE CASCADE,
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE CASCADE,
    reason varchar(16) NOT NULL CHECK (reason IN ('AD_SPAM', 'ABUSE', 'ILLEGAL', 'OTHER')),
    feedback_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (comment_id, account_id)
);
