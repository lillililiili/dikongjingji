-- 仅开发环境：由 @Profile("dev") 的 liveDevelopmentDataFlyway 加载，不在 db/migration 下，生产不会执行。
-- 平台内直播（M20）的示例直播间，文字取自 2026-11-14 修订版原型的 Discover（“直播”频道）、LiveNotice、LiveRoom 画板：
--   机器狗园区夜巡演示（直播中，鹰眼智能巡检）、编程无人机开箱与首飞（直播中，示例无人机厂家）、
--   编程机器人入门（预告，示例机器人商家）、园区夜巡方案怎么定：服务商答疑（预告，鹰眼智能巡检）、
--   光伏电站热斑巡检现场连线、机器人避障闯关：体验馆开放日（已结束，有合作平台回看链接）。
-- 主办机构都是巡检、商城、身份开发数据里已有的示例机构。没有授权图片，cover_url 为空：预览构建按直播间 ID 用画板图片
-- （apps/mobile/src/features/live/data/preview/live-preview-covers.ts）。流是本机模拟服务的：播放地址是 App 内的示例视频。
-- 回看链接是 example.com 下的占位地址（不能真的打开），平台名是“示例直播平台”。
-- 讲解项指向商城、巡检开发数据里的真实对象（App 点进去读真实数据）；标题是主办方写的快照。
-- dev-merchant（示例机器人商家的操作员，交易开发数据建）在这里开通直播主办，用来跑 infra/scripts/dev-live-host.cjs。
-- 预告时间相对执行当天；下一行的日期由后端启动时填入，所以每天第一次启动会重排，直播中的两场也重新从“刚开播”算起。
-- 对齐日期：${devSeedDate}
-- 可重复执行：先删掉这些直播间（讲解项、观看、提醒、点赞、弹幕、回调记录随之删掉）再插入。

DELETE FROM live_room WHERE id IN ('f4000000-0000-4000-8000-000000000001', 'f4000000-0000-4000-8000-000000000002', 'f4000000-0000-4000-8000-000000000003', 'f4000000-0000-4000-8000-000000000004', 'f4000000-0000-4000-8000-000000000005', 'f4000000-0000-4000-8000-000000000006');

INSERT INTO live_room (
    id, host_org_id, host_name, title, subtitle, cover_url, city_code, scheduled_at, speaker_name, speaker_title, topics,
    status, started_at, ended_at, end_reason, stream_id, push_url, play_url, replay_url, replay_platform_name,
    current_item_seq, viewer_count, like_count, comment_count
)
VALUES
    ('f4000000-0000-4000-8000-000000000001', 'f2000000-0000-4000-8000-000000000001', '鹰眼智能巡检',
     '机器狗园区夜巡演示', '看一台机器狗怎样走完园区夜间路线', NULL, '370500',
     now() - interval '47 minutes', '王工', '项目负责人',
     E'夜间路线怎么定，重点点位怎么选\n机器狗到点位后拍什么、记什么\n发现异常后，现场怎么复核和留记录',
     'LIVE', now() - interval '42 minutes', NULL, NULL, 'fake-seed-night-patrol',
     'rtmp://127.0.0.1:1935/live/fake-seed-night-patrol', '/static/videos/preview/live-night-patrol.mp4', NULL, NULL,
     1, 12400, 23600, 5),
    ('f4000000-0000-4000-8000-000000000002', 'c1000000-0000-4000-8000-000000000004', '示例无人机厂家',
     '编程无人机开箱与首飞', '东营低空与机器人体验馆现场开箱', NULL, '370500',
     now() - interval '20 minutes', NULL, NULL,
     E'开箱看看套装里有什么\n室内第一次悬停怎么飞\n图形化编程让无人机自己飞一圈',
     'LIVE', now() - interval '18 minutes', NULL, NULL, 'fake-seed-drone-unboxing',
     'rtmp://127.0.0.1:1935/live/fake-seed-drone-unboxing', '/static/videos/preview/live-drone-unboxing.mp4', NULL, NULL,
     1, 3860, 7400, 5),
    ('f4000000-0000-4000-8000-000000000003', 'c1000000-0000-4000-8000-000000000001', '示例机器人商家',
     '编程机器人入门', '图形化编程第一课：拖积木让机器人走迷宫', NULL, '370500',
     (DATE '${devSeedDate}' + 2 + TIME '19:30') AT TIME ZONE 'Asia/Shanghai', '李老师', '课程讲师',
     E'套装里有哪些零件，怎么拼\n拖积木写第一段程序\n让机器人自己走出迷宫',
     'SCHEDULED', NULL, NULL, NULL, 'fake-seed-robot-intro',
     'rtmp://127.0.0.1:1935/live/fake-seed-robot-intro', '/static/videos/preview/live-drone-unboxing.mp4', NULL, NULL,
     NULL, 0, 0, 0),
    ('f4000000-0000-4000-8000-000000000004', 'f2000000-0000-4000-8000-000000000001', '鹰眼智能巡检',
     '园区夜巡方案怎么定：服务商答疑', '一场讲清园区夜间巡检的方案和报价', NULL, '370500',
     (DATE '${devSeedDate}' + 5 + TIME '19:30') AT TIME ZONE 'Asia/Shanghai', '王工', '项目负责人',
     E'夜间路线怎么定，重点点位怎么选\n机器狗到点位后拍什么、记什么\n发现异常后，现场怎么复核和留记录',
     'SCHEDULED', NULL, NULL, NULL, 'fake-seed-patrol-qa',
     'rtmp://127.0.0.1:1935/live/fake-seed-patrol-qa', '/static/videos/preview/live-night-patrol.mp4', NULL, NULL,
     NULL, 0, 0, 0),
    ('f4000000-0000-4000-8000-000000000005', '22222222-2222-2222-2222-222222222222', '云翼科技',
     '光伏电站热斑巡检现场连线', '热斑是怎么被找出来的', NULL, '370500',
     (DATE '${devSeedDate}' - 21 + TIME '14:00') AT TIME ZONE 'Asia/Shanghai', NULL, NULL, NULL,
     'ENDED', (DATE '${devSeedDate}' - 21 + TIME '14:02') AT TIME ZONE 'Asia/Shanghai',
     (DATE '${devSeedDate}' - 21 + TIME '14:54') AT TIME ZONE 'Asia/Shanghai', 'STREAM', 'fake-seed-solar',
     'rtmp://127.0.0.1:1935/live/fake-seed-solar', '/static/videos/preview/live-night-patrol.mp4',
     'https://live.example.com/replay/solar-hotspot', '示例直播平台',
     NULL, 2860, 4100, 0),
    ('f4000000-0000-4000-8000-000000000006', 'a1000000-0000-4000-8000-000000000004', '东营低空与机器人体验馆',
     '机器人避障闯关：体验馆开放日', '开放日现场，孩子们怎么闯关', NULL, '370500',
     (DATE '${devSeedDate}' - 26 + TIME '10:00') AT TIME ZONE 'Asia/Shanghai', NULL, NULL, NULL,
     'ENDED', (DATE '${devSeedDate}' - 26 + TIME '10:00') AT TIME ZONE 'Asia/Shanghai',
     (DATE '${devSeedDate}' - 26 + TIME '10:38') AT TIME ZONE 'Asia/Shanghai', 'STREAM', 'fake-seed-open-day',
     'rtmp://127.0.0.1:1935/live/fake-seed-open-day', '/static/videos/preview/live-drone-unboxing.mp4',
     'https://live.example.com/replay/open-day', '示例直播平台',
     NULL, 1920, 3300, 0);

-- 讲解清单：园区安防巡检（巡检开发数据的园区综合巡检服务）、巡检机器狗（商城租赁商品）；编程教学无人机、智能飞行电池；
-- 编程机器人探索套装。
INSERT INTO live_room_item (room_id, seq, item_type, ref_id, title)
VALUES
    ('f4000000-0000-4000-8000-000000000001', 1, 'INSPECTION_SERVICE', '77777777-7777-7777-7777-777777777777', '园区安防巡检'),
    ('f4000000-0000-4000-8000-000000000001', 2, 'PRODUCT', 'c2000000-0000-4000-8000-000000000401', '巡检机器狗'),
    ('f4000000-0000-4000-8000-000000000002', 1, 'PRODUCT', 'c2000000-0000-4000-8000-000000000201', '编程教学无人机 入门套装'),
    ('f4000000-0000-4000-8000-000000000002', 2, 'PRODUCT', 'c2000000-0000-4000-8000-000000000301', '智能飞行电池 适配教学机'),
    ('f4000000-0000-4000-8000-000000000003', 1, 'PRODUCT', 'c2000000-0000-4000-8000-000000000101', '编程机器人探索套装');

-- 两场直播中的弹幕（原型 LiveRoom 的 chat）。开发种子的弹幕没有作者账号；观众名已经打码，主播显示机构名。
INSERT INTO live_comment (room_id, seq, side, author_id, author_name, body, created_at)
VALUES
    ('f4000000-0000-4000-8000-000000000001', 1, 'VIEWER', NULL, '王**', '机器狗一晚上能巡几圈？', now() - interval '6 minutes'),
    ('f4000000-0000-4000-8000-000000000001', 2, 'HOST', NULL, '鹰眼智能巡检', '这个园区 6 个点位，一圈 40 分钟，一晚 6 圈', now() - interval '5 minutes'),
    ('f4000000-0000-4000-8000-000000000001', 3, 'VIEWER', NULL, '张**', '热成像能看清人吗', now() - interval '4 minutes'),
    ('f4000000-0000-4000-8000-000000000001', 4, 'VIEWER', NULL, '李**', '按项目报价大概什么范围', now() - interval '3 minutes'),
    ('f4000000-0000-4000-8000-000000000001', 5, 'HOST', NULL, '鹰眼智能巡检', '点右侧“讲解”进服务详情发需求，1 个工作日内报价', now() - interval '2 minutes'),
    ('f4000000-0000-4000-8000-000000000002', 1, 'VIEWER', NULL, '赵**', '室内能飞吗？', now() - interval '5 minutes'),
    ('f4000000-0000-4000-8000-000000000002', 2, 'HOST', NULL, '示例无人机厂家', '可以，带桨叶保护罩，客厅就能练悬停', now() - interval '4 minutes'),
    ('f4000000-0000-4000-8000-000000000002', 3, 'VIEWER', NULL, '钱**', '10 岁孩子能自己编程吗', now() - interval '3 minutes'),
    ('f4000000-0000-4000-8000-000000000002', 4, 'HOST', NULL, '示例无人机厂家', '图形化编程，拖积木就行，配套有 12 节课', now() - interval '2 minutes'),
    ('f4000000-0000-4000-8000-000000000002', 5, 'VIEWER', NULL, '孙**', '电池能飞多久', now() - interval '1 minutes');

-- dev-merchant 开通直播主办（限定在示例机器人商家），能管“编程机器人入门”这一场。
INSERT INTO identity_user_role (user_id, role_id, organization_id)
SELECT 'c1000000-0000-4000-8000-0000000000a1', id, 'c1000000-0000-4000-8000-000000000001'
FROM identity_role WHERE role_code = 'LIVE_HOST'
ON CONFLICT DO NOTHING;
