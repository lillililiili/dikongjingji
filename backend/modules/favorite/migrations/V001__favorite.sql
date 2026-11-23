-- 收藏（计划 08 S5-6，契约 contracts/openapi-favorite.yaml 1.0.0）。商品对比只在 App 本机做，不入库。
-- 对象只存类型 + 稳定 ID，不引用商城、巡检、专业服务、活动、场馆、内容的表；列表展示时经端口从各模块读。
-- 表引用 identity_user，排在身份迁移之后（ModuleFlywayConfiguration）。

CREATE TABLE favorite_item (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    -- PRODUCT 商品；INSPECTION_SERVICE 巡检服务；PRO_SERVICE 专业服务；ACTIVITY 活动；VENUE 场馆；CONTENT 图文或视频。
    target_type varchar(24) NOT NULL CHECK (target_type IN ('PRODUCT', 'INSPECTION_SERVICE', 'PRO_SERVICE', 'ACTIVITY',
                                                            'VENUE', 'CONTENT')),
    target_id uuid NOT NULL,
    -- 收藏时对象的标题快照：对象下架后列表里还能显示它叫什么。
    title varchar(160) NOT NULL CHECK (length(btrim(title)) > 0),
    favorited_at timestamptz NOT NULL,
    -- 取消收藏的时间；为空表示还在收藏。取消后 10 分钟内再收藏是撤销，恢复这一条（收藏时间和标题不变）。
    removed_at timestamptz,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    -- 每个账号对同一对象最多一条（取消了的也是这一条）。
    CONSTRAINT uq_favorite_item_target UNIQUE (account_id, target_type, target_id)
);

-- 收藏页按类型分页，新收藏的在前。
CREATE INDEX idx_favorite_item_account ON favorite_item (account_id, target_type, favorited_at DESC, id DESC)
    WHERE removed_at IS NULL;
