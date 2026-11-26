-- 仅开发环境。仅更新原有演示数据，运营录入的数据不在范围内。
-- 与每天重排的示例同步：${devSeedDate}
UPDATE content_article AS t
SET cover_url = '/api/v1/dev-media/images/preview/' || sample.file_name
FROM (VALUES
    ('f3000000-0000-4000-8000-000000000001', 'content-park-patrol.webp'),
    ('f3000000-0000-4000-8000-000000000002', 'content-survey.jpg'),
    ('f3000000-0000-4000-8000-000000000003', 'content-solar.webp'),
    ('f3000000-0000-4000-8000-000000000004', 'content-drone-buy.jpg'),
    ('f3000000-0000-4000-8000-000000000005', 'content-robot-kit.jpg'),
    ('f3000000-0000-4000-8000-000000000006', 'content-flight-check.jpg'),
    ('f3200000-0000-4000-8000-000000000001', 'video-robot-coding.jpg'),
    ('f3200000-0000-4000-8000-000000000002', 'video-dog-patrol.jpg'),
    ('f3200000-0000-4000-8000-000000000003', 'video-aerial-yellow-river.jpg'),
    ('f3200000-0000-4000-8000-000000000004', 'video-contest-recap.jpg'),
    ('f3200000-0000-4000-8000-000000000005', 'video-survey-3days.jpg'),
    ('f3200000-0000-4000-8000-000000000006', 'video-study-camp-day.jpg')) AS sample(id, file_name)
WHERE t.id = sample.id::uuid;

UPDATE content_topic AS t
SET cover_url = '/api/v1/dev-media/images/preview/' || sample.file_name
FROM (VALUES
    ('f3100000-0000-4000-8000-000000000001', 'content-topic-workshop.jpg')) AS sample(id, file_name)
WHERE t.id = sample.id::uuid;

UPDATE content_article AS t
SET video_url = '/api/v1/dev-media/videos/preview/' || sample.file_name
FROM (VALUES
    ('f3200000-0000-4000-8000-000000000001', 'robot-coding.mp4'),
    ('f3200000-0000-4000-8000-000000000002', 'dog-patrol.mp4'),
    ('f3200000-0000-4000-8000-000000000003', 'aerial-yellow-river.mp4'),
    ('f3200000-0000-4000-8000-000000000004', 'contest-recap.mp4'),
    ('f3200000-0000-4000-8000-000000000005', 'survey-3days.mp4'),
    ('f3200000-0000-4000-8000-000000000006', 'study-camp-day.mp4')) AS sample(id, file_name)
WHERE t.id = sample.id::uuid;

UPDATE content_article_block SET image_url = '/api/v1/dev-media/images/preview/content-patrol-drone.jpg'
WHERE id = md5('dev-content-block:1:7')::uuid AND block_type = 'IMAGE';
