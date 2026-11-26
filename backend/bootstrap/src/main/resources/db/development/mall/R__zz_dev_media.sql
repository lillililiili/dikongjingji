-- 仅开发环境。仅更新原有演示数据，运营录入的数据不在范围内。
UPDATE mall_product AS t
SET cover_url = '/api/v1/dev-media/images/preview/' || sample.file_name
FROM (VALUES
    ('c2000000-0000-4000-8000-000000000101', 'robot-rover.jpg'),
    ('c2000000-0000-4000-8000-000000000102', 'robot-arm.jpg'),
    ('c2000000-0000-4000-8000-000000000201', 'drone-edu.jpg'),
    ('c2000000-0000-4000-8000-000000000202', 'drone-aerial.jpg'),
    ('c2000000-0000-4000-8000-000000000203', 'drone-aerial.jpg'),
    ('c2000000-0000-4000-8000-000000000301', 'parts-battery.jpg'),
    ('c2000000-0000-4000-8000-000000000302', 'venue-indoor.jpg'),
    ('c2000000-0000-4000-8000-000000000303', 'parts-battery.jpg'),
    ('c2000000-0000-4000-8000-000000000401', 'robot-dog.jpg'),
    ('c2000000-0000-4000-8000-000000000402', 'drone-aerial.jpg'),
    ('c2000000-0000-4000-8000-000000000403', 'drone-survey.jpg'),
    ('c2000000-0000-4000-8000-000000000404', 'field-outdoor.jpg')) AS sample(id, file_name)
WHERE t.id = sample.id::uuid;
