-- 仅开发环境：由 @Profile("dev") 的 contentDevelopmentDataFlyway 加载，不在 db/migration 下，生产不会执行。
-- 发现页的示例视频（1.2.0，docs/redesign/07-视频与直播实现计划.md §2），标题、说明和话题取自 2026-11-14 修订版原型的
-- Discover、VideoFeed、ContentVideo 画板，全部 sample = true（页面标“示例”）。
-- 没有视频存储，也没有授权图片：video_url、cover_url 一律为空，预览构建按 ID 用 App 内由画板图片合成的示例视频和封面
-- （apps/mobile/src/features/content/data/preview/content-preview-videos.ts），公开构建不显示这些页面。
-- 作者：原型里的“示例科创器材店”“示例研学机构”是商城、活动开发数据里的机构；编程挑战赛回顾是城市运营（平台）发的。
-- “内容中提到”的第一项是视频挂的入口（商品、服务、活动、场馆，开发数据里真实的 ID）。
-- 发布时间相对执行当天，和图文交错：推荐频道第一页（10 条）仍有全部 6 篇图文；运营排序给出“视频”频道和视频流的顺序
-- （画板 Discover 的视频频道：编程机器人、机器狗、航拍、挑战赛、航测、研学营）。
-- 日期从下一行占位符的北京日期推算，不用数据库自己的当前日期（数据库按 UTC 时北京零点到八点它还是前一天）。
-- 对齐日期：${devSeedDate}
-- 可重复执行：视频按 ID 覆盖（不删，开发库里点过的赞、发过的评论留着），关联对象先删后插。

INSERT INTO content_article (id, kind, title, author_kind, author_org_id, author_name, sponsored, city_code, cover_url,
                             sample, status, published_at, video_duration_seconds, video_orientation, video_url,
                             video_caption, video_tags, video_rank)
VALUES
    ('f3200000-0000-4000-8000-000000000001', 'VIDEO', '3 分钟看懂编程机器人', 'MERCHANT', 'c1000000-0000-4000-8000-000000000001', '示例机器人商家', true, '370500', NULL,
     true, 'PUBLISHED', ((DATE '${devSeedDate}' - 16) + time '10:00') AT TIME ZONE 'Asia/Shanghai', 192, 'LANDSCAPE', NULL,
     '它是怎么“看见”障碍的？超声波加循线传感器，3 分钟讲清楚。视频为厂家录播演示，非实时操控。', '编程机器人 少儿编程 避障', 60),
    ('f3200000-0000-4000-8000-000000000002', 'VIDEO', '机器狗园区夜巡实录', 'PROVIDER', 'f2000000-0000-4000-8000-000000000001', '鹰眼智能巡检', false, '370500', NULL,
     true, 'PUBLISHED', ((DATE '${devSeedDate}' - 23) + time '10:00') AT TIME ZONE 'Asia/Shanghai', 108, 'PORTRAIT', NULL,
     '凌晨两点的物流园区，机器狗沿着预设路线巡逻，热成像看得比人清楚。', '园区巡检 机器狗 东营', 50),
    ('f3200000-0000-4000-8000-000000000003', 'VIDEO', '4K 航拍黄河口', 'MERCHANT', 'c1000000-0000-4000-8000-000000000003', '示例科创器材店', true, '370500', NULL,
     true, 'PUBLISHED', ((DATE '${devSeedDate}' - 21) + time '10:00') AT TIME ZONE 'Asia/Shanghai', 58, 'LANDSCAPE', NULL,
     '黄河入海口的秋天，从 120 米高度看过去是这样的。', '航拍 黄河口 东营风光', 40),
    ('f3200000-0000-4000-8000-000000000004', 'VIDEO', '编程挑战赛精彩回顾', 'PLATFORM', NULL, '东营城市运营中心', false, '370500', NULL,
     true, 'PUBLISHED', ((DATE '${devSeedDate}' - 19) + time '10:00') AT TIME ZONE 'Asia/Shanghai', 125, 'PORTRAIT', NULL,
     '上一站 60 个孩子、12 条航线，这一站东营体验馆见。', '青少年无人机编程挑战赛 赛事', 30),
    ('f3200000-0000-4000-8000-000000000005', 'VIDEO', '无人机航测三天出图', 'PROVIDER', 'f2000000-0000-4000-8000-000000000002', '示例测绘服务商', false, '370500', NULL,
     true, 'PUBLISHED', ((DATE '${devSeedDate}' - 31) + time '10:00') AT TIME ZONE 'Asia/Shanghai', 156, 'LANDSCAPE', NULL,
     '从外业飞行到正射影像，一个 2 平方公里园区的测绘全过程。', '无人机航测 三维建模 工程', 20),
    ('f3200000-0000-4000-8000-000000000006', 'VIDEO', '研学营的一天', 'PROVIDER', 'a1000000-0000-4000-8000-000000000002', '示例研学机构', false, '370500', NULL,
     true, 'PUBLISHED', ((DATE '${devSeedDate}' - 33) + time '10:00') AT TIME ZONE 'Asia/Shanghai', 90, 'PORTRAIT', NULL,
     '上午认识机器人，下午自己写程序让它走迷宫，孩子们不想下课。', '研学 机器人 周末去哪儿', 10)
ON CONFLICT (id) DO UPDATE
SET kind = EXCLUDED.kind,
    title = EXCLUDED.title,
    author_kind = EXCLUDED.author_kind,
    author_org_id = EXCLUDED.author_org_id,
    author_name = EXCLUDED.author_name,
    sponsored = EXCLUDED.sponsored,
    city_code = EXCLUDED.city_code,
    cover_url = EXCLUDED.cover_url,
    sample = EXCLUDED.sample,
    status = EXCLUDED.status,
    published_at = EXCLUDED.published_at,
    video_duration_seconds = EXCLUDED.video_duration_seconds,
    video_orientation = EXCLUDED.video_orientation,
    video_url = EXCLUDED.video_url,
    video_caption = EXCLUDED.video_caption,
    video_tags = EXCLUDED.video_tags,
    video_rank = EXCLUDED.video_rank,
    updated_at = now();

DELETE FROM content_article_ref WHERE article_id IN ('f3200000-0000-4000-8000-000000000001', 'f3200000-0000-4000-8000-000000000002', 'f3200000-0000-4000-8000-000000000003', 'f3200000-0000-4000-8000-000000000004', 'f3200000-0000-4000-8000-000000000005', 'f3200000-0000-4000-8000-000000000006');

-- 内容中提到：第一项是视频挂的入口（画板 VideoFeed 的“关联商品 / 服务 / 活动”，ContentVideo 的“看商品”“去体验”）。
INSERT INTO content_article_ref (article_id, sort_order, ref_type, ref_id, title)
VALUES
    ('f3200000-0000-4000-8000-000000000001', 10, 'PRODUCT', 'c2000000-0000-4000-8000-000000000101', '编程机器人探索套装'),
    ('f3200000-0000-4000-8000-000000000001', 20, 'VENUE', 'b2000000-0000-4000-8000-000000000001', '东营低空与机器人体验馆'),
    ('f3200000-0000-4000-8000-000000000002', 10, 'INSPECTION_SERVICE', '77777777-7777-7777-7777-777777777777', '园区综合巡检服务'),
    ('f3200000-0000-4000-8000-000000000002', 20, 'PRODUCT', 'c2000000-0000-4000-8000-000000000401', '巡检机器狗'),
    ('f3200000-0000-4000-8000-000000000003', 10, 'PRODUCT', 'c2000000-0000-4000-8000-000000000202', '4K 航拍无人机 标准版'),
    ('f3200000-0000-4000-8000-000000000004', 10, 'ACTIVITY', 'a2000000-0000-4000-8000-000000000001', '青少年无人机编程挑战赛（东营站）'),
    ('f3200000-0000-4000-8000-000000000005', 10, 'PRO_SERVICE', 'f2100000-0000-4000-8000-000000000005', '无人机航测与三维建模'),
    ('f3200000-0000-4000-8000-000000000006', 10, 'ACTIVITY', 'a2000000-0000-4000-8000-000000000002', '机器人研学体验');
