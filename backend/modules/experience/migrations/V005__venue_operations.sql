-- S3-1 运营端“场馆与设备”（docs/redesign/08-后续开发计划.md §2、§5 D11，契约 contracts/openapi-ops-venue.yaml 1.0.0、
-- contracts/openapi-experience.yaml 1.3.0）：营业时间、入口检票与迟到规则、场次停售、运营端新建的幂等键和改动记录。
-- V004 留给别的批次，本迁移不依赖它。

-- 营业时间（北京时间，每天开门、关门）和休息日说明（比如“周一闭馆”）。三项都由运营端填；opening_note 仍是 App 上显示的
-- 那一行字：运营端保存时由这三项生成（“09:30–18:00 · 周一闭馆”），老数据没填结构化字段时保持原样。
-- 检票：开场前多少分钟开始检票（原来写死 30 分钟）；迟到：开场后多少分钟内还能入场，为空表示到场次结束都能入场。
ALTER TABLE venue
    ADD COLUMN opens_at time,
    ADD COLUMN closes_at time,
    ADD COLUMN closed_note varchar(40),
    ADD COLUMN checkin_opens_minutes integer NOT NULL DEFAULT 30
        CHECK (checkin_opens_minutes BETWEEN 0 AND 240),
    ADD COLUMN late_entry_minutes integer CHECK (late_entry_minutes BETWEEN 0 AND 240),
    ADD COLUMN published_at timestamptz,
    -- 运营端新建草稿的幂等键：同一个键再提交一次返回同一个场馆。开发种子建的没有。
    ADD COLUMN ops_create_key uuid,
    ADD CONSTRAINT ck_venue_opening_hours
        CHECK ((opens_at IS NULL) = (closes_at IS NULL) AND (opens_at IS NULL OR opens_at < closes_at));

CREATE UNIQUE INDEX uq_venue_ops_create_key ON venue (ops_create_key) WHERE ops_create_key IS NOT NULL;

ALTER TABLE venue_project ADD COLUMN ops_create_key uuid;
CREATE UNIQUE INDEX uq_venue_project_ops_create_key ON venue_project (ops_create_key) WHERE ops_create_key IS NOT NULL;

ALTER TABLE venue_device ADD COLUMN ops_create_key uuid;
CREATE UNIQUE INDEX uq_venue_device_ops_create_key ON venue_device (ops_create_key) WHERE ops_create_key IS NOT NULL;

-- 场次多一个 STOPPED 停售：不再接新预约（App 场次列表不显示），已有的预约照常到馆、核销、取消退款。
-- 有有效预约（待付款、待到馆、已核销）的场次只能停售，不能取消（CANCELLED）。V001 里是列约束，名字是 venue_session_status_check。
ALTER TABLE venue_session DROP CONSTRAINT venue_session_status_check;
ALTER TABLE venue_session
    ADD CONSTRAINT ck_venue_session_status CHECK (status IN ('ACTIVE', 'STOPPED', 'CANCELLED'));

-- 批量生成时“同一项目同一开始时间已经有了就跳过”（只算没取消的场次）由运营服务在锁住项目后检查。不加唯一索引：
-- 开发种子每天按当天重排场次，同一条语句里新旧开始时间会短暂重叠，非延迟的唯一索引会让重排失败。

-- 运营端改动记录，只追加。subject 是改的对象（场馆、项目、场次、设备），都挂在一个场馆下；operator_name 是当时的显示名。
CREATE TABLE venue_ops_change (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    venue_id uuid NOT NULL REFERENCES venue(id) ON DELETE RESTRICT,
    subject_type varchar(16) NOT NULL CHECK (subject_type IN ('VENUE', 'PROJECT', 'SESSION', 'DEVICE')),
    subject_id uuid NOT NULL,
    action varchar(16) NOT NULL
        CHECK (action IN ('CREATE', 'UPDATE', 'PUBLISH', 'OFFLINE', 'ENABLE', 'DISABLE', 'GENERATE',
                          'STOP_SALE', 'RESUME_SALE', 'CANCEL')),
    summary varchar(300) NOT NULL,
    operator_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    operator_name varchar(80) NOT NULL,
    changed_at timestamptz NOT NULL
);

CREATE INDEX idx_venue_ops_change_venue ON venue_ops_change (venue_id, changed_at DESC, id DESC);
