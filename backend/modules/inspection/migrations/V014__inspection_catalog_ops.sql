-- S3-1 运营端“巡检服务目录”（docs/redesign/08-后续开发计划.md §2 S3-1，契约 contracts/openapi-ops-inspection-catalog.yaml 1.0.0）。
-- 平台运营在 Web 运营端维护巡检类目和巡检服务（服务、价格、城市覆盖、服务项目、详情项、上下架）。
-- 公开读取（openapi-inspection-catalog.yaml）不变：仍只给 status = 'ACTIVE' 且资料齐全、在这个城市覆盖的服务。
-- V013 留给别的批次。

-- 服务改动要带页面读到的版本号（乐观锁）。旧行从 0 开始。
ALTER TABLE inspection_service
    ADD COLUMN version integer NOT NULL DEFAULT 0 CHECK (version >= 0);

-- 运营端的改动记录，只追加。operator_name 是改的时候运营账号的显示名快照。
-- 类目的主键是编码（varchar），subject_id 用由编码算出的固定 UUID（名字空间 “inspection-category:” + 编码），
-- summary 里写类目编码和名称，页面按 subject_type 分开看。
CREATE TABLE inspection_catalog_change (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type varchar(16) NOT NULL CHECK (subject_type IN ('CATEGORY', 'SERVICE')),
    subject_id uuid NOT NULL,
    action varchar(16) NOT NULL
        CHECK (action IN ('CREATE', 'UPDATE', 'PUBLISH', 'OFFLINE', 'ENABLE', 'DISABLE')),
    summary varchar(300) NOT NULL,
    operator_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    operator_name varchar(80) NOT NULL,
    changed_at timestamptz NOT NULL
);

CREATE INDEX idx_inspection_catalog_change_subject
    ON inspection_catalog_change (subject_type, subject_id, changed_at DESC, id DESC);

-- 运营端列表按更新时间倒序。
CREATE INDEX idx_inspection_service_ops_list
    ON inspection_service (status, updated_at DESC, id);
