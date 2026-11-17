-- 仅开发环境：由 @Profile("dev") 的 activityDevelopmentDataFlyway 加载，不在 db/migration 下，生产不会执行。
-- 内容取自 2026-11-14 修订版原型的五个示例活动，机构、地点、价格都是示例，不是真实活动。
-- 场次时间相对“执行当天”排，保证总能看到报名中、已满可候补、已截止几种状态。
-- 可重复迁移只在校验和变化时重跑；下一行的日期由后端启动时填入，所以每天第一次启动会重排场次。
-- 对齐日期：${devSeedDate}

INSERT INTO identity_organization (id, organization_code, organization_name, organization_type)
VALUES
    ('a1000000-0000-4000-8000-000000000001', 'DEV-ACT-YOUTH-TECH', '示例青少年科技机构', 'PROVIDER'),
    ('a1000000-0000-4000-8000-000000000002', 'DEV-ACT-STUDY', '示例研学机构', 'PROVIDER'),
    ('a1000000-0000-4000-8000-000000000003', 'DEV-ACT-TRAINING', '示例培训机构', 'PROVIDER'),
    ('a1000000-0000-4000-8000-000000000004', 'DEV-ACT-VENUE', '东营低空与机器人体验馆', 'PROVIDER'),
    ('a1000000-0000-4000-8000-000000000005', 'DEV-ACT-CAMP', '黄河口户外飞行营地', 'PROVIDER')
ON CONFLICT (id) DO UPDATE
SET organization_code = EXCLUDED.organization_code,
    organization_name = EXCLUDED.organization_name,
    organization_type = EXCLUDED.organization_type,
    updated_at = now();

INSERT INTO activity (
    id, organizer_id, co_organizer_id, type, title, subtitle, cover_url, city_code,
    venue_name, venue_address, venue_location, schedule_note,
    price_mode, price_amount_minor, min_age, max_age,
    eligibility_note, seat_note, refund_note,
    waitlist_allowed, team_enrollment_allowed, status
)
VALUES
    ('a2000000-0000-4000-8000-000000000001',
     'a1000000-0000-4000-8000-000000000001', 'a1000000-0000-4000-8000-000000000004',
     'CONTEST', '青少年无人机编程挑战赛（东营站）', '图形化编程 · 现场闯关 · 完赛证明', NULL, '370500',
     '东营低空与机器人体验馆', '东营区示例路 66 号',
     ST_SetSRID(ST_MakePoint(118.5823, 37.4487), 4326)::geography, NULL,
     'PER_PERSON', 12000, 8, 15,
     '8–15 岁，由监护人代报名并到场',
     '参赛的孩子每人报 1 个名额，陪同家长不用报名',
     '活动开始 48 小时前可免费取消；之后不退报名费，可转让同场次名额。活动延期或取消全额退款。',
     true, true, 'PUBLISHED'),
    ('a2000000-0000-4000-8000-000000000002',
     'a1000000-0000-4000-8000-000000000002', 'a1000000-0000-4000-8000-000000000004',
     'STUDY_TOUR', '机器人研学体验', '动手编程 · 避障闯关', NULL, '370500',
     '东营低空与机器人体验馆', '东营区示例路 66 号',
     ST_SetSRID(ST_MakePoint(118.5823, 37.4487), 4326)::geography, NULL,
     'PER_PERSON', 16800, 6, 12,
     '6–12 岁，由监护人代报名并到场',
     '参加的孩子每人报 1 个名额，陪同家长不用报名',
     '活动开始 48 小时前可免费取消；之后不退报名费，可转让同场次名额。活动延期或取消全额退款。',
     true, true, 'PUBLISHED'),
    ('a2000000-0000-4000-8000-000000000003',
     'a1000000-0000-4000-8000-000000000002', 'a1000000-0000-4000-8000-000000000005',
     'STUDY_TOUR', '黄河口低空研学一日营', '户外飞行 · 自然课堂 · 含接送', NULL, '370500',
     '黄河口户外飞行营地', '黄河口示例路 8 号',
     ST_SetSRID(ST_MakePoint(118.9412, 37.7605), 4326)::geography, '全天 · 含接送',
     'PER_PERSON', 26800, 8, 15,
     '8–15 岁，由监护人代报名，营地统一接送',
     '参加的孩子每人报 1 个名额，家长不随行',
     '活动开始 48 小时前可免费取消；之后不退报名费，可转让同场次名额。活动延期或取消全额退款。',
     true, false, 'PUBLISHED'),
    ('a2000000-0000-4000-8000-000000000004',
     'a1000000-0000-4000-8000-000000000003', NULL,
     'TRAINING', '多旋翼无人机驾驶员培训', '理论 + 实操 · 考前辅导', NULL, '370500',
     '示例培训机构训练场', '东营区示例路 120 号',
     ST_SetSRID(ST_MakePoint(118.6104, 37.4312), 4326)::geography, '理论 + 实操',
     'INQUIRY', NULL, 16, NULL,
     '16 岁以上，本人报名',
     '需提供身份证件，费用按机型在报价中确认',
     NULL,
     false, false, 'PUBLISHED'),
    ('a2000000-0000-4000-8000-000000000005',
     'a1000000-0000-4000-8000-000000000004', 'a1000000-0000-4000-8000-000000000004',
     'OPEN_DAY', '无人机开放体验日', '到馆参观 · 模拟飞行 · 互动讲解', NULL, '370500',
     '东营低空与机器人体验馆', '东营区示例路 66 号',
     ST_SetSRID(ST_MakePoint(118.5823, 37.4487), 4326)::geography, NULL,
     'FREE', NULL, NULL, NULL,
     '不限年龄，未成年人需家长陪同',
     '每人报 1 个名额，陪同家长不用报名',
     '免费活动，来不了请提前取消，把名额让给别人。',
     true, true, 'PUBLISHED'),
    -- 已下线的活动：列表和详情都不应返回。
    ('a2000000-0000-4000-8000-000000000006',
     'a1000000-0000-4000-8000-000000000001', NULL,
     'CONTEST', '已下线的示例赛事', NULL, NULL, '370500',
     '东营低空与机器人体验馆', '东营区示例路 66 号', NULL, NULL,
     'FREE', NULL, NULL, NULL, NULL, NULL, NULL,
     false, false, 'OFFLINE')
ON CONFLICT (id) DO UPDATE
SET organizer_id = EXCLUDED.organizer_id,
    co_organizer_id = EXCLUDED.co_organizer_id,
    type = EXCLUDED.type,
    title = EXCLUDED.title,
    subtitle = EXCLUDED.subtitle,
    cover_url = EXCLUDED.cover_url,
    city_code = EXCLUDED.city_code,
    venue_name = EXCLUDED.venue_name,
    venue_address = EXCLUDED.venue_address,
    venue_location = EXCLUDED.venue_location,
    schedule_note = EXCLUDED.schedule_note,
    price_mode = EXCLUDED.price_mode,
    price_amount_minor = EXCLUDED.price_amount_minor,
    min_age = EXCLUDED.min_age,
    max_age = EXCLUDED.max_age,
    eligibility_note = EXCLUDED.eligibility_note,
    seat_note = EXCLUDED.seat_note,
    refund_note = EXCLUDED.refund_note,
    waitlist_allowed = EXCLUDED.waitlist_allowed,
    team_enrollment_allowed = EXCLUDED.team_enrollment_allowed,
    status = EXCLUDED.status,
    updated_at = now();

-- 场次：day_offset 是距执行当天的天数，hhmm 是北京时间开始时刻，close_days 是开始前几天的 18:00 截止报名。
INSERT INTO activity_session (
    id, activity_id, label, starts_at, ends_at, check_in_opens_at, enrollment_closes_at,
    capacity, enrolled_count
)
SELECT seed.id::uuid,
       seed.activity_id::uuid,
       seed.label,
       starts.starts_at,
       starts.starts_at + seed.duration,
       starts.starts_at - interval '30 minutes',
       LEAST(
           starts.starts_at,
           (date_trunc('day', starts.starts_at AT TIME ZONE 'Asia/Shanghai')
               - make_interval(days => seed.close_days) + interval '18 hours')
               AT TIME ZONE 'Asia/Shanghai'),
       seed.capacity,
       seed.enrolled_count
FROM (
    VALUES
        -- 挑战赛：上午余 12、下午已满（可候补）、下周余 30
        ('a3000000-0000-4000-8000-000000000101', 'a2000000-0000-4000-8000-000000000001', '上午', 12, '09:00', interval '3 hours', 1, 60, 48),
        ('a3000000-0000-4000-8000-000000000102', 'a2000000-0000-4000-8000-000000000001', '下午', 12, '14:00', interval '3 hours', 1, 60, 60),
        ('a3000000-0000-4000-8000-000000000103', 'a2000000-0000-4000-8000-000000000001', '上午', 19, '09:00', interval '3 hours', 1, 60, 30),
        -- 研学体验：三场都有余位
        ('a3000000-0000-4000-8000-000000000201', 'a2000000-0000-4000-8000-000000000002', '上午', 5, '09:30', interval '150 minutes', 1, 30, 18),
        ('a3000000-0000-4000-8000-000000000202', 'a2000000-0000-4000-8000-000000000002', '下午', 5, '14:00', interval '150 minutes', 1, 30, 24),
        ('a3000000-0000-4000-8000-000000000203', 'a2000000-0000-4000-8000-000000000002', '上午', 12, '09:30', interval '150 minutes', 1, 30, 10),
        -- 一日营：三场都已满（可候补），列表里能看到“已满可候补”
        ('a3000000-0000-4000-8000-000000000301', 'a2000000-0000-4000-8000-000000000003', '全天', 6, '08:30', interval '9 hours', 1, 40, 40),
        ('a3000000-0000-4000-8000-000000000302', 'a2000000-0000-4000-8000-000000000003', '全天', 13, '08:30', interval '9 hours', 1, 40, 40),
        ('a3000000-0000-4000-8000-000000000303', 'a2000000-0000-4000-8000-000000000003', '全天', 20, '08:30', interval '9 hours', 1, 40, 40),
        -- 培训：三个班期
        ('a3000000-0000-4000-8000-000000000401', 'a2000000-0000-4000-8000-000000000004', '第 1 期 · 20 天', 15, '09:00', interval '20 days', 3, 30, 22),
        ('a3000000-0000-4000-8000-000000000402', 'a2000000-0000-4000-8000-000000000004', '第 2 期 · 20 天', 50, '09:00', interval '20 days', 3, 30, 0),
        ('a3000000-0000-4000-8000-000000000403', 'a2000000-0000-4000-8000-000000000004', '第 3 期 · 20 天', 78, '09:00', interval '20 days', 3, 30, 0),
        -- 开放日：明天那场今天 18:00 截止（下午执行时可看到“报名截止”），其余可报
        ('a3000000-0000-4000-8000-000000000501', 'a2000000-0000-4000-8000-000000000005', '上午', 1, '10:00', interval '2 hours', 1, 80, 55),
        ('a3000000-0000-4000-8000-000000000502', 'a2000000-0000-4000-8000-000000000005', '下午', 11, '14:00', interval '2 hours', 1, 80, 40),
        ('a3000000-0000-4000-8000-000000000503', 'a2000000-0000-4000-8000-000000000005', '上午', 18, '10:00', interval '2 hours', 1, 80, 20),
        -- 已过去的一场：详情里显示“已结束”，不影响列表
        ('a3000000-0000-4000-8000-000000000504', 'a2000000-0000-4000-8000-000000000005', '上午', -3, '10:00', interval '2 hours', 1, 80, 80)
) AS seed(id, activity_id, label, day_offset, hhmm, duration, close_days, capacity, enrolled_count)
CROSS JOIN LATERAL (
    SELECT ((date_trunc('day', now() AT TIME ZONE 'Asia/Shanghai')
            + make_interval(days => seed.day_offset)
            + seed.hhmm::interval) AT TIME ZONE 'Asia/Shanghai') AS starts_at
) AS starts
ON CONFLICT (id) DO UPDATE
SET activity_id = EXCLUDED.activity_id,
    label = EXCLUDED.label,
    starts_at = EXCLUDED.starts_at,
    ends_at = EXCLUDED.ends_at,
    check_in_opens_at = EXCLUDED.check_in_opens_at,
    enrollment_closes_at = EXCLUDED.enrollment_closes_at,
    capacity = EXCLUDED.capacity,
    enrolled_count = EXCLUDED.enrolled_count,
    updated_at = now();
