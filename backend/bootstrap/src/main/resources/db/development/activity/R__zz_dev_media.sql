-- 仅开发环境。仅更新原有演示数据，运营录入的数据不在范围内。
-- 与每天重排的示例同步：${devSeedDate}
UPDATE activity AS t
SET cover_url = '/api/v1/dev-media/images/preview/' || sample.file_name
FROM (VALUES
    ('a2000000-0000-4000-8000-000000000001', 'drone-edu.jpg'),
    ('a2000000-0000-4000-8000-000000000002', 'robot-workshop.jpg'),
    ('a2000000-0000-4000-8000-000000000003', 'camp-yellow-river.jpg'),
    ('a2000000-0000-4000-8000-000000000004', 'drone-aerial.jpg'),
    ('a2000000-0000-4000-8000-000000000005', 'venue-exterior.jpg')) AS sample(id, file_name)
WHERE t.id = sample.id::uuid;
