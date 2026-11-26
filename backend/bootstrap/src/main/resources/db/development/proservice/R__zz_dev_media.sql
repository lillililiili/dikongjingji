-- 仅开发环境。仅更新原有演示数据，运营录入的数据不在范围内。
UPDATE pro_service AS t
SET cover_url = '/api/v1/dev-media/images/preview/' || sample.file_name
FROM (VALUES
    ('f2100000-0000-4000-8000-000000000001', 'proservice-park-aerial.webp'),
    ('f2100000-0000-4000-8000-000000000002', 'proservice-solar.webp'),
    ('f2100000-0000-4000-8000-000000000003', 'proservice-drone-city.jpg'),
    ('f2100000-0000-4000-8000-000000000004', 'proservice-event.jpg'),
    ('f2100000-0000-4000-8000-000000000005', 'proservice-survey-kit.jpg'),
    ('f2100000-0000-4000-8000-000000000006', 'proservice-park-aerial.webp'),
    ('f2100000-0000-4000-8000-000000000007', 'proservice-solar.webp'),
    ('f2100000-0000-4000-8000-000000000008', 'proservice-corridor.webp'),
    ('f2100000-0000-4000-8000-000000000009', 'proservice-drone-parts.jpg'),
    ('f2100000-0000-4000-8000-000000000010', 'proservice-drone-city.jpg'),
    ('f2100000-0000-4000-8000-000000000011', 'proservice-robot-care.jpg'),
    ('f2100000-0000-4000-8000-000000000012', 'proservice-robot-dog-check.webp'),
    ('f2100000-0000-4000-8000-000000000013', 'proservice-tech-robot.jpg'),
    ('f2100000-0000-4000-8000-000000000014', 'proservice-flight-sim.jpg'),
    ('f2100000-0000-4000-8000-000000000015', 'proservice-event-robot.jpg'),
    ('f2100000-0000-4000-8000-000000000016', 'proservice-software.jpg')) AS sample(id, file_name)
WHERE t.id = sample.id::uuid;
