-- 开发种子可引用限定路径下的本机媒体；运营写入沿用原有 HTTPS 验证。
ALTER TABLE course
    DROP CONSTRAINT course_cover_url_check,
    ADD CONSTRAINT course_cover_url_check CHECK (
        cover_url LIKE 'https://%' OR cover_url ~ '^/api/v1/dev-media/images/preview/[a-z0-9-]+[.](jpg|png|webp)$'
    );
ALTER TABLE course_lesson
    DROP CONSTRAINT course_lesson_video_url_check,
    ADD CONSTRAINT course_lesson_video_url_check CHECK (
        video_url LIKE 'https://%' OR video_url ~ '^/api/v1/dev-media/videos/preview/[a-z0-9-]+[.]mp4$'
    );
