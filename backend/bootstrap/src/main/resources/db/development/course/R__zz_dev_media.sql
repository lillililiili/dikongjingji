-- 仅开发环境。仅更新原有演示数据，运营录入的数据不在范围内。
UPDATE course AS t
SET cover_url = '/api/v1/dev-media/images/preview/' || sample.file_name
FROM (VALUES
    ('d7c00000-0000-4000-8000-000000000001', 'course-flight.jpg'),
    ('d7c00000-0000-4000-8000-000000000002', 'course-robot.jpg')) AS sample(id, file_name)
WHERE t.id = sample.id::uuid;

UPDATE course_lesson
SET video_url = '/api/v1/dev-media/videos/preview/' ||
    CASE WHEN course_id = 'd7c00000-0000-4000-8000-000000000002' THEN 'course-robot-coding.mp4'
         WHEN ((sort_order - 1) / 3) % 2 = 0 THEN 'course-flight-principle.mp4'
         ELSE 'course-flight-control.mp4' END
WHERE (course_id = 'd7c00000-0000-4000-8000-000000000001' AND id::text LIKE 'd7c20000-0000-4000-8000-0000000001%')
   OR (course_id = 'd7c00000-0000-4000-8000-000000000002' AND id::text LIKE 'd7c20000-0000-4000-8000-0000000002%');
