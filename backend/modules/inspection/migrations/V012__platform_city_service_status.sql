-- S3-1 运营端“城市开通”（docs/redesign/08-后续开发计划.md §2 S3-1，契约 contracts/openapi-ops-city.yaml 1.0.0、
-- contracts/openapi-platform-city.yaml 1.1.0）：平台城市目录的开通状态。
--   OPEN      已开通：App 城市面板“已开通城市”
--   PREPARING 筹备中：App 城市面板“筹备中”，可以先浏览全国商品与线上体验
--   CLOSED    未开通：面板不列，只能搜索到
-- 和原有的 status（ACTIVE / INACTIVE，行政区划是否可选）是两回事；可重复迁移 R__platform_city_reference.sql 只写 status，
-- 不会覆盖这里的开通状态。只由运营端写（每次改都在 platform_city_service_status_change 里留一条）。
ALTER TABLE platform_city
    ADD COLUMN service_status varchar(16) NOT NULL DEFAULT 'CLOSED'
        CHECK (service_status IN ('OPEN', 'PREPARING', 'CLOSED')),
    ADD COLUMN service_status_changed_at timestamptz;

CREATE INDEX idx_platform_city_service_status
    ON platform_city (status, service_status, city_pinyin, city_code);

-- 开通状态的改动记录，只追加。operator_name 是改的时候运营账号的显示名快照。
CREATE TABLE platform_city_service_status_change (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    city_code varchar(16) NOT NULL REFERENCES platform_city(city_code) ON DELETE RESTRICT,
    from_status varchar(16) NOT NULL CHECK (from_status IN ('OPEN', 'PREPARING', 'CLOSED')),
    to_status varchar(16) NOT NULL CHECK (to_status IN ('OPEN', 'PREPARING', 'CLOSED')),
    note varchar(200) NOT NULL,
    operator_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    operator_name varchar(80) NOT NULL,
    changed_at timestamptz NOT NULL,
    CHECK (from_status <> to_status)
);

CREATE INDEX idx_platform_city_service_status_change_city
    ON platform_city_service_status_change (city_code, changed_at DESC, id DESC);

-- 一期试运营城市东营的初始开通状态写在 R__platform_city_reference.sql 末尾：全新数据库里城市行是那份可重复迁移插入的，
-- 它在所有版本迁移之后才执行，写在这里时表还是空的。
