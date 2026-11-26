-- 按城市发布首页轮播。正式库为空；图片使用运营素材键，开发种子使用开发媒体服务。
CREATE TABLE content_home_slide (
    city_code varchar(16) NOT NULL CHECK (city_code ~ '^[A-Z0-9]{4,16}$'),
    id varchar(48) NOT NULL CHECK (id ~ '^[a-z0-9][a-z0-9-]{0,47}$'),
    tag varchar(60) NOT NULL,
    title_first varchar(60) NOT NULL,
    title_second varchar(60) NOT NULL,
    description_first varchar(120) NOT NULL,
    description_second varchar(120) NOT NULL,
    action_label varchar(60) NOT NULL,
    image_ref varchar(220) NOT NULL CHECK (
        image_ref ~ '^ops-media/[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}[.](jpg|png|webp)$'
        OR image_ref ~ '^/api/v1/dev-media/images/(home|preview)/[a-z0-9-]+[.](jpg|png|webp)$'
    ),
    image_alt varchar(120) NOT NULL,
    tone varchar(12) NOT NULL CHECK (tone IN ('SKY', 'SUN', 'VIOLET', 'MINT')),
    target varchar(16) NOT NULL CHECK (target IN ('ACTIVITY', 'PLAY_GAMES', 'EXPERIENCE')),
    status varchar(12) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'PUBLISHED', 'OFFLINE')),
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (city_code, id)
);
CREATE INDEX idx_content_home_slide_published ON content_home_slide(city_code, sort_order, id)
    WHERE status = 'PUBLISHED';

ALTER TABLE content_article_block
    DROP CONSTRAINT content_article_block_image_url_check,
    ADD CONSTRAINT content_article_block_image_url_check CHECK (
        image_url LIKE 'https://%' OR image_url ~ '^/api/v1/dev-media/images/preview/[a-z0-9-]+[.](jpg|png|webp)$'
    );
