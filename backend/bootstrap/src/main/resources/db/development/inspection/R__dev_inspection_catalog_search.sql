-- 开发种子（仅 dev profile 的 flyway_history_inspection_development 加载），2026-11-16。
-- 给 R__dev_inspection_catalog.sql 里的 8 个示例服务补上筛选和距离排序要用的数据：
-- 巡检方式、是否含巡检报告、在东营 / 济南的服务中心点，以及入口页的场景副标题和服务流程。
-- 电缆通道巡检（aaaa…）故意不给中心点，用来验证“没有距离的排最后、不显示距离”。
-- 服务保障没有确认过的承诺，这里也不写。

UPDATE inspection_service
SET includes_report = id IN (
        '33333333-3333-3333-3333-333333333333',
        '44444444-4444-4444-4444-444444444444',
        '55555555-5555-5555-5555-555555555555',
        '66666666-6666-6666-6666-666666666666'
    ),
    updated_at = now()
WHERE service_no LIKE 'INS-SVC-DEV-%';

DELETE FROM inspection_service_method
WHERE service_id IN (
    SELECT id FROM inspection_service WHERE service_no LIKE 'INS-SVC-DEV-%'
);

INSERT INTO inspection_service_method (service_id, method_code) VALUES
    ('33333333-3333-3333-3333-333333333333', 'UAV'),
    ('44444444-4444-4444-4444-444444444444', 'ROBOT'),
    ('55555555-5555-5555-5555-555555555555', 'UAV'),
    ('66666666-6666-6666-6666-666666666666', 'UAV'),
    ('77777777-7777-7777-7777-777777777777', 'UAV'),
    ('77777777-7777-7777-7777-777777777777', 'ROBOT'),
    ('88888888-8888-4888-8888-888888888888', 'ROBOT'),
    ('99999999-9999-4999-8999-999999999999', 'UAV'),
    ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 'ROBOT');

INSERT INTO inspection_service_coverage (service_id, city_code, center_point)
SELECT service_id, city_code, ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography
FROM (
    VALUES
        ('33333333-3333-3333-3333-333333333333'::uuid, '370500', 118.6747, 37.4346),
        ('44444444-4444-4444-4444-444444444444'::uuid, '370500', 118.5200, 37.4600),
        ('55555555-5555-5555-5555-555555555555'::uuid, '370500', 118.7900, 37.6200),
        ('66666666-6666-6666-6666-666666666666'::uuid, '370500', 118.4900, 37.3300),
        ('77777777-7777-7777-7777-777777777777'::uuid, '370500', 118.6600, 37.4500),
        ('88888888-8888-4888-8888-888888888888'::uuid, '370500', 118.5800, 37.4900),
        ('99999999-9999-4999-8999-999999999999'::uuid, '370500', 118.7300, 37.3900),
        ('33333333-3333-3333-3333-333333333333'::uuid, '370100', 117.0009, 36.6758),
        ('66666666-6666-6666-6666-666666666666'::uuid, '370100', 117.2300, 36.7200)
) center(service_id, city_code, longitude, latitude)
ON CONFLICT (service_id, city_code) DO UPDATE
SET center_point = EXCLUDED.center_point,
    updated_at = now();

-- 入口页场景格的副标题，和已验收的 M01 画面一致。分类本身是正式迁移 V011 里的基础数据。
UPDATE inspection_category category
SET subtitle = dev.subtitle,
    updated_at = now()
FROM (
    VALUES
        ('POWER', '线路·杆塔·变电站'),
        ('PHOTOVOLTAIC', '热斑·组件·效率'),
        ('OIL_GAS', '管线·泄漏·安全'),
        ('THERMAL', '热成像·管网评估'),
        ('PARK', '安防·设备·环境'),
        ('EQUIPMENT', '状态·故障·记录'),
        ('PERIODIC', '周期计划·持续跟踪')
) dev(code, subtitle)
WHERE category.code = dev.code
  AND category.subtitle IS DISTINCT FROM dev.subtitle;

-- 服务流程四步，编码和文案与已验收的 M01 画面一致。
DELETE FROM inspection_portal_content
WHERE content_type = 'PROCESS'
  AND city_code IS NULL
  AND item_code NOT IN ('REQUEST', 'QUOTE', 'EXECUTE', 'REPORT');

INSERT INTO inspection_portal_content (
    city_code, content_type, item_code, title, sort_order
) VALUES
    (NULL, 'PROCESS', 'REQUEST', '提交需求', 10),
    (NULL, 'PROCESS', 'QUOTE', '方案报价', 20),
    (NULL, 'PROCESS', 'EXECUTE', '现场执行', 30),
    (NULL, 'PROCESS', 'REPORT', '报告验收', 40)
ON CONFLICT (COALESCE(city_code, ''), content_type, item_code) DO UPDATE
SET title = EXCLUDED.title,
    subtitle = NULL,
    sort_order = EXCLUDED.sort_order,
    status = 'ACTIVE',
    updated_at = now();
