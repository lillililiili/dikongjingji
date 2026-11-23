-- S3-1 运营端“专业服务目录”（docs/redesign/08-后续开发计划.md §2 S3-1，契约 contracts/openapi-ops-proservice.yaml 1.0.0）。
-- 平台运营在 Web 运营端维护航拍、测绘、设备维护、技术支持四类服务：服务商、类型、价格、上架城市、展示内容、上下架。
-- 公开读取（openapi-proservice.yaml）不变：仍只给 status = 'PUBLISHED' 且在这个城市上架的服务。
-- 运营新建的服务先是 UNPUBLISHED（写入时显式给出，列默认值 PUBLISHED 不变，开发种子照旧）。

-- 运营端的改动记录，只追加。operator_name 是改的时候运营账号的显示名快照。
CREATE TABLE pro_service_change (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id uuid NOT NULL REFERENCES pro_service(id) ON DELETE RESTRICT,
    action varchar(16) NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'PUBLISH', 'OFFLINE')),
    summary varchar(300) NOT NULL,
    operator_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    operator_name varchar(80) NOT NULL,
    changed_at timestamptz NOT NULL
);

CREATE INDEX idx_pro_service_change_service ON pro_service_change (service_id, changed_at DESC, id DESC);

-- 运营端列表按更新时间倒序。
CREATE INDEX idx_pro_service_ops_list ON pro_service (status, updated_at DESC, id);
