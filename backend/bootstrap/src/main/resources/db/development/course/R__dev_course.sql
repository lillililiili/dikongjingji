-- 仅开发环境：由 @Profile("dev") 的 courseDevelopmentDataFlyway 加载，不在 db/migration 下，生产不会执行。
-- S5-5：内容取自 2026-11-14 修订版原型的 MallCourse、CourseFly、CourseDetail、MyCourses，课程、价格、讲师都是示例。
--
-- 课程编号 d7c00000-…，章 d7c10000-…，课时 d7c20000-…（后三位：课程号 + 序号）。
-- 开发环境没有视频存储，课时的 video_url 为空；预览构建按课时 ID 播放由画板图片合成的示例视频（App 的 course-preview-videos.ts）。
-- 开课机构、活动、商城商品、体验项目都由别的开发数据建（ModuleFlywayConfiguration 的 DependsOn）：
--   示例培训机构 a1000000-…-0003（活动开发数据）、示例机器人商家 c1000000-…-0001（商城开发数据）、示例研学机构 a1000000-…-0002；
--   体验中心的“无人机飞行原理（试听）”b1000000-…-0005；编程机器人探索套装 c2000000-…-0101、编程教学无人机 入门套装 c2000000-…-0201。

INSERT INTO course (
    id, organization_id, delivery_mode, topic, title, subtitle, cover_url, price_amount_minor, validity_days,
    age_note, instructor_name, instructor_title, includes, extra_specs, intro, experience_item_id, simulation_item_id,
    activity_id, schedule_label, sort_order, status, first_listed_at
)
VALUES
    -- 画板 CourseFly：¥199、12 课时、试听 2 课、365 天有效。
    ('d7c00000-0000-4000-8000-000000000001', 'a1000000-0000-4000-8000-000000000003', 'ONLINE', 'DRONE',
     '无人机飞行原理 线上课', '从升力到航线规划，12 节录播课，含课后练习', NULL, 19900, 365,
     '10 岁以上零基础', '示例讲师', '平台认证讲师',
     '[{"name": "录播课程", "quantity": 12}, {"name": "课后练习", "quantity": 4}, {"name": "电子证书", "quantity": 1}]',
     '[{"label": "练习", "value": "每章配练习与模拟任务"}, {"label": "证书", "value": "完成后发放电子证书"}]',
     '["每节约 15 分钟，手机上随时学，学到哪里自动记住。", "前两节可以免费试听；开通后 365 天内可以反复观看。"]',
     'b1000000-0000-4000-8000-000000000005', 'b1000000-0000-4000-8000-000000000002', NULL, NULL, 30, 'LISTED', now()),
    -- 画板 CourseDetail / MyCourses：随“编程机器人探索套装 · 标准套装”附带的数字课程，不单独出售，16 课时。
    ('d7c00000-0000-4000-8000-000000000002', 'c1000000-0000-4000-8000-000000000001', 'ONLINE', 'PROGRAMMING',
     '编程机器人入门课', '配套数字课程：从开箱到综合项目，16 课时', NULL, NULL, NULL,
     '8 岁以上', NULL, NULL,
     '[{"name": "录播课程", "quantity": 16}]', '[]', '[]',
     NULL, NULL, NULL, NULL, 20, 'LISTED', now()),
    -- 线下班：在课程页显示，点进对应的活动报名（画板 CourseCamp → 机器人研学体验）。
    ('d7c00000-0000-4000-8000-000000000003', 'a1000000-0000-4000-8000-000000000002', 'OFFLINE', 'ROBOT',
     '青少年机器人研学（线下班）', NULL, NULL, NULL, NULL, NULL, NULL, NULL, '[]', '[]', '[]', NULL, NULL,
     'a2000000-0000-4000-8000-000000000002', '按场次', 20, 'LISTED', now()),
    -- 线下班：画板 CourseCert 的执照培训 → 多旋翼无人机驾驶员培训（价格面议）。
    ('d7c00000-0000-4000-8000-000000000004', 'a1000000-0000-4000-8000-000000000003', 'OFFLINE', 'CERTIFICATE',
     '无人机操作员执照培训（线下班）', NULL, NULL, NULL, NULL, NULL, NULL, NULL, '[]', '[]', '[]', NULL, NULL,
     'a2000000-0000-4000-8000-000000000004', '按班期', 10, 'LISTED', now()),
    -- 线下班：画板 CourseRobotIntro 的入门线下班挂在一个已下线的活动上，列表里不应出现（没有对应活动时不显示）。
    ('d7c00000-0000-4000-8000-000000000005', 'c1000000-0000-4000-8000-000000000001', 'OFFLINE', 'PROGRAMMING',
     '编程机器人入门课 线下班', NULL, NULL, NULL, NULL, NULL, NULL, NULL, '[]', '[]', '[]', NULL, NULL,
     'a2000000-0000-4000-8000-000000000006', '8 次课', 25, 'LISTED', now()),
    -- 草稿不应出现在列表里。
    ('d7c00000-0000-4000-8000-000000000006', 'a1000000-0000-4000-8000-000000000003', 'ONLINE', 'DRONE',
     '未上架的示例课程', NULL, NULL, 9900, NULL, NULL, NULL, NULL, '[]', '[]', '[]', NULL, NULL,
     NULL, NULL, 99, 'DRAFT', NULL)
ON CONFLICT (id) DO UPDATE
SET organization_id = EXCLUDED.organization_id,
    delivery_mode = EXCLUDED.delivery_mode,
    topic = EXCLUDED.topic,
    title = EXCLUDED.title,
    subtitle = EXCLUDED.subtitle,
    cover_url = EXCLUDED.cover_url,
    price_amount_minor = EXCLUDED.price_amount_minor,
    validity_days = EXCLUDED.validity_days,
    age_note = EXCLUDED.age_note,
    instructor_name = EXCLUDED.instructor_name,
    instructor_title = EXCLUDED.instructor_title,
    includes = EXCLUDED.includes,
    extra_specs = EXCLUDED.extra_specs,
    intro = EXCLUDED.intro,
    experience_item_id = EXCLUDED.experience_item_id,
    simulation_item_id = EXCLUDED.simulation_item_id,
    activity_id = EXCLUDED.activity_id,
    schedule_label = EXCLUDED.schedule_label,
    sort_order = EXCLUDED.sort_order,
    status = EXCLUDED.status,
    first_listed_at = coalesce(course.first_listed_at, EXCLUDED.first_listed_at),
    updated_at = now();

-- 附带关系：编程机器人探索套装附带“编程机器人入门课”，编程教学无人机 入门套装附带“无人机飞行原理”。
INSERT INTO course_bundle (course_id, product_id)
VALUES
    ('d7c00000-0000-4000-8000-000000000002', 'c2000000-0000-4000-8000-000000000101'),
    ('d7c00000-0000-4000-8000-000000000001', 'c2000000-0000-4000-8000-000000000201')
ON CONFLICT (course_id, product_id) DO NOTHING;

-- 章节。重新运行时先把序号挪开，免得和新序号撞上唯一约束。
UPDATE course_chapter SET sort_order = sort_order + 100000
WHERE course_id IN ('d7c00000-0000-4000-8000-000000000001', 'd7c00000-0000-4000-8000-000000000002');
UPDATE course_lesson SET sort_order = sort_order + 100000
WHERE course_id IN ('d7c00000-0000-4000-8000-000000000001', 'd7c00000-0000-4000-8000-000000000002');

INSERT INTO course_chapter (id, course_id, title, sort_order)
VALUES
    ('d7c10000-0000-4000-8000-000000000011', 'd7c00000-0000-4000-8000-000000000001', '第 1 章 飞起来的道理', 1),
    ('d7c10000-0000-4000-8000-000000000012', 'd7c00000-0000-4000-8000-000000000001', '第 2 章 安全与法规', 2),
    ('d7c10000-0000-4000-8000-000000000013', 'd7c00000-0000-4000-8000-000000000001', '第 3 章 基础操控', 3),
    ('d7c10000-0000-4000-8000-000000000014', 'd7c00000-0000-4000-8000-000000000001', '第 4 章 航线规划', 4),
    ('d7c10000-0000-4000-8000-000000000021', 'd7c00000-0000-4000-8000-000000000002', '第 1 章 认识机器人', 1),
    ('d7c10000-0000-4000-8000-000000000022', 'd7c00000-0000-4000-8000-000000000002', '第 2 章 图形化编程', 2),
    ('d7c10000-0000-4000-8000-000000000023', 'd7c00000-0000-4000-8000-000000000002', '第 3 章 传感器进阶', 3),
    ('d7c10000-0000-4000-8000-000000000024', 'd7c00000-0000-4000-8000-000000000002', '第 4 章 综合项目', 4)
ON CONFLICT (id) DO UPDATE
SET course_id = EXCLUDED.course_id,
    title = EXCLUDED.title,
    sort_order = EXCLUDED.sort_order,
    updated_at = now();

-- 课时：飞行原理 12 节（每节约 15 分钟，前两节试听），编程机器人入门课 16 节（每节约 12 分钟，画板 CourseDetail 的目录）。
INSERT INTO course_lesson (id, course_id, chapter_id, title, duration_seconds, trial, video_url, sort_order)
SELECT ('d7c20000-0000-4000-8000-0000000001' || lpad(seed.no::text, 2, '0'))::uuid,
       'd7c00000-0000-4000-8000-000000000001'::uuid,
       ('d7c10000-0000-4000-8000-00000000001' || seed.chapter)::uuid,
       seed.title, seed.seconds, seed.no <= 2, NULL, seed.no
FROM (VALUES
    (1, 1, '升力从哪里来', 860),
    (2, 1, '四旋翼怎样保持平衡', 905),
    (3, 1, '认识遥控器与飞控', 880),
    (4, 2, '飞行前检查清单', 840),
    (5, 2, '禁飞区与限高', 910),
    (6, 2, '天气与风', 870),
    (7, 3, '起飞与悬停', 930),
    (8, 3, '前后左右平移', 895),
    (9, 3, '降落与返航', 860),
    (10, 4, '认识地图与航点', 900),
    (11, 4, '规划一条巡检航线', 945),
    (12, 4, '结课测验', 820)
) AS seed(no, chapter, title, seconds)
UNION ALL
SELECT ('d7c20000-0000-4000-8000-0000000002' || lpad(seed.no::text, 2, '0'))::uuid,
       'd7c00000-0000-4000-8000-000000000002'::uuid,
       ('d7c10000-0000-4000-8000-00000000002' || seed.chapter)::uuid,
       seed.title, seed.seconds, false, NULL, seed.no
FROM (VALUES
    (1, 1, '开箱与安全须知', 700),
    (2, 1, '认识传感器', 720),
    (3, 1, '连接编程环境', 735),
    (4, 1, '第一次让它动起来', 710),
    (5, 2, '顺序与循环', 725),
    (6, 2, '条件判断', 715),
    (7, 2, '让机器人避障', 740),
    (8, 2, '小任务：走出迷宫', 760),
    (9, 3, '巡线原理', 705),
    (10, 3, '巡线实战', 730),
    (11, 3, '声音与灯光', 690),
    (12, 3, '小任务：自动巡线', 750),
    (13, 4, '项目构思', 700),
    (14, 4, '分步实现', 745),
    (15, 4, '调试与优化', 720),
    (16, 4, '结课展示', 680)
) AS seed(no, chapter, title, seconds)
ON CONFLICT (id) DO UPDATE
SET course_id = EXCLUDED.course_id,
    chapter_id = EXCLUDED.chapter_id,
    title = EXCLUDED.title,
    duration_seconds = EXCLUDED.duration_seconds,
    trial = EXCLUDED.trial,
    video_url = EXCLUDED.video_url,
    sort_order = EXCLUDED.sort_order,
    updated_at = now();
