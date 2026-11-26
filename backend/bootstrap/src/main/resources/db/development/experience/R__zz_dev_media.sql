-- 仅开发环境。仅更新原有演示数据，运营录入的数据不在范围内。
-- 与每天重排的示例同步：${devSeedDate}
UPDATE experience_item AS t
SET cover_url = '/api/v1/dev-media/images/preview/' || sample.file_name
FROM (VALUES
    ('b1000000-0000-4000-8000-000000000001', 'sim-drone.jpg'),
    ('b1000000-0000-4000-8000-000000000002', 'drone-edu.jpg'),
    ('b1000000-0000-4000-8000-000000000003', 'demo-video.jpg'),
    ('b1000000-0000-4000-8000-000000000004', 'robot-classroom.jpg'),
    ('b1000000-0000-4000-8000-000000000005', 'drone-edu.jpg')) AS sample(id, file_name)
WHERE t.id = sample.id::uuid;

UPDATE venue AS t
SET cover_url = '/api/v1/dev-media/images/preview/' || sample.file_name
FROM (VALUES
    ('b2000000-0000-4000-8000-000000000001', 'venue-indoor.jpg'),
    ('b2000000-0000-4000-8000-000000000002', 'field-outdoor.jpg')) AS sample(id, file_name)
WHERE t.id = sample.id::uuid;

UPDATE venue_device AS t
SET image_url = '/api/v1/dev-media/images/preview/' || sample.file_name
FROM (VALUES
    ('b5000000-0000-4000-8000-000000000101', 'robot-rover.jpg'),
    ('b5000000-0000-4000-8000-000000000102', 'drone-aerial.jpg'),
    ('b5000000-0000-4000-8000-000000000103', 'robot-dog.jpg'),
    ('b5000000-0000-4000-8000-000000000104', 'parts-battery.jpg'),
    ('b5000000-0000-4000-8000-000000000105', 'venue-indoor.jpg'),
    ('b5000000-0000-4000-8000-000000000106', 'drone-survey.jpg'),
    ('b5000000-0000-4000-8000-000000000201', 'robot-rover.jpg'),
    ('b5000000-0000-4000-8000-000000000202', 'drone-aerial.jpg'),
    ('b5000000-0000-4000-8000-000000000203', 'robot-dog.jpg'),
    ('b5000000-0000-4000-8000-000000000204', 'parts-battery.jpg'),
    ('b5000000-0000-4000-8000-000000000205', 'drone-survey.jpg'),
    ('b5000000-0000-4000-8000-000000000206', 'venue-indoor.jpg')) AS sample(id, file_name)
WHERE t.id = sample.id::uuid;
