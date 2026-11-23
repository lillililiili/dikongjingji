-- 仅开发环境：城市开通状态的示例（S3-1，巡检迁移 V012），照 2026-11-14 修订版原型 CitySheet 画板：
-- 已开通 东营；筹备中 济南、青岛、潍坊。其余城市保持未开通（只能搜索到）。运营端改过以后，这份种子只在内容变了时重跑。
UPDATE platform_city AS city
SET service_status = seed.service_status,
    service_status_changed_at = now(),
    version = city.version + 1,
    updated_at = now()
FROM (VALUES
    ('370500', 'OPEN'),
    ('370100', 'PREPARING'),
    ('370200', 'PREPARING'),
    ('370700', 'PREPARING')
) AS seed(city_code, service_status)
WHERE city.city_code = seed.city_code
  AND city.service_status <> seed.service_status;
