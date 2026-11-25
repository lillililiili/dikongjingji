-- 仅开发环境。仅更新原有演示数据，运营录入的数据不在范围内。
-- 与每天重排的示例同步：${devSeedDate}
UPDATE live_room AS t
SET cover_url = '/api/v1/dev-media/images/preview/' || sample.file_name
FROM (VALUES
    ('f4000000-0000-4000-8000-000000000001', 'content-park-patrol.webp'),
    ('f4000000-0000-4000-8000-000000000002', 'content-ref-flight-guide.jpg'),
    ('f4000000-0000-4000-8000-000000000003', 'robot-classroom.jpg'),
    ('f4000000-0000-4000-8000-000000000004', 'content-ref-survey-model.jpg'),
    ('f4000000-0000-4000-8000-000000000005', 'content-solar.webp'),
    ('f4000000-0000-4000-8000-000000000006', 'proservice-tech-robot.jpg')) AS sample(id, file_name)
WHERE t.id = sample.id::uuid;

UPDATE live_room SET play_url = replace(play_url, '/static/videos/preview/', '/api/v1/dev-media/videos/preview/')
WHERE id::text LIKE 'f4000000-0000-4000-8000-00000000000_' AND play_url LIKE '/static/videos/preview/%';

-- 这两场没有实际回放，清除原种子留的占位站外链接。
UPDATE live_room SET replay_url = NULL, replay_platform_name = NULL
WHERE id IN ('f4000000-0000-4000-8000-000000000005', 'f4000000-0000-4000-8000-000000000006')
  AND replay_url LIKE 'https://%example.com/%';
