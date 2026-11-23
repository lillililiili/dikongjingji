-- S3-1 运营端“活动与场次”（docs/redesign/08-后续开发计划.md §2、§5 D11；契约 contracts/openapi-ops-activity.yaml 1.0.0、
-- contracts/openapi-activity.yaml 1.3.0）。只由平台运营在运营端写，每次改动在 activity_ops_change 里留一条。

-- 报名须知（多行文字，App 详情“报名须知”）；第一次上线的时间（上线过的活动不能删，只能下线）。
ALTER TABLE activity
    ADD COLUMN enrollment_notice varchar(2000),
    ADD COLUMN first_published_at timestamptz;

-- 图集：按顺序最多 9 张，只放受控 HTTPS 地址；封面仍是 activity.cover_url。
CREATE TABLE activity_gallery_image (
    activity_id uuid NOT NULL REFERENCES activity(id) ON DELETE CASCADE,
    sort_order integer NOT NULL CHECK (sort_order BETWEEN 1 AND 9),
    url varchar(500) NOT NULL CHECK (url LIKE 'https://%'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (activity_id, sort_order),
    UNIQUE (activity_id, url)
);

-- 场次售卖状态：STOPPED 停售后 App 不能再报名（读成报名截止），已有的报名不受影响。和 status（ACTIVE / CANCELLED）是两回事。
ALTER TABLE activity_session
    ADD COLUMN sale_status varchar(16) NOT NULL DEFAULT 'ON_SALE'
        CHECK (sale_status IN ('ON_SALE', 'STOPPED'));

-- 运营改动记录，只追加。subject 是活动本身或它的一个场次（场次删了记录还在，subject_id 不加外键）。
-- operator_name 是操作时运营账号的显示名快照。
CREATE TABLE activity_ops_change (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    activity_id uuid NOT NULL REFERENCES activity(id) ON DELETE RESTRICT,
    subject_type varchar(16) NOT NULL CHECK (subject_type IN ('ACTIVITY', 'SESSION')),
    subject_id uuid NOT NULL,
    action varchar(16) NOT NULL
        CHECK (action IN ('CREATE', 'UPDATE', 'PUBLISH', 'OFFLINE', 'DELETE', 'STOP_SALE', 'RESUME_SALE')),
    summary varchar(200) NOT NULL,
    operator_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    operator_name varchar(80) NOT NULL,
    changed_at timestamptz NOT NULL
);

CREATE INDEX idx_activity_ops_change_activity ON activity_ops_change (activity_id, changed_at DESC, id DESC);

-- 运营列表按更新时间倒序。
CREATE INDEX idx_activity_ops_list ON activity (status, updated_at DESC, id DESC);
