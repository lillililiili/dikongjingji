-- 仅开发环境：由 @Profile("dev") 的 experienceDevelopmentDataFlyway 加载，不在 db/migration 下，生产不会执行。
-- 内容取自 2026-11-14 修订版原型的体验中心、场馆详情和馆内设备，场馆、项目、价格都是示例。
-- 场次按“执行当天起 7 天”生成。可重复迁移只在校验和变化时重跑；下一行的日期由后端启动时填入，
-- 所以每天第一次启动会重新生成场次。
-- 对齐日期：${devSeedDate}

INSERT INTO identity_organization (id, organization_code, organization_name, organization_type)
VALUES
    ('a1000000-0000-4000-8000-000000000004', 'DEV-ACT-VENUE', '东营低空与机器人体验馆', 'PROVIDER'),
    ('a1000000-0000-4000-8000-000000000005', 'DEV-ACT-CAMP', '黄河口户外飞行营地', 'PROVIDER')
ON CONFLICT (id) DO UPDATE
SET organization_code = EXCLUDED.organization_code,
    organization_name = EXCLUDED.organization_name,
    organization_type = EXCLUDED.organization_type,
    updated_at = now();

INSERT INTO experience_item (id, type, title, summary, login_required, sort_order, status)
VALUES
    ('b1000000-0000-4000-8000-000000000001', 'SIMULATION', '机器人避障模拟', '约 3 分钟 · 免登录可试 · 手机即可', false, 10, 'PUBLISHED'),
    ('b1000000-0000-4000-8000-000000000002', 'SIMULATION', '无人机起降与悬停', '约 4 分钟 · 免登录可试', false, 20, 'PUBLISHED'),
    ('b1000000-0000-4000-8000-000000000003', 'RECORDED_DEMO', '3 分钟看懂编程机器人', '视频 3:12 · 示例机器人商家', false, 30, 'PUBLISHED'),
    ('b1000000-0000-4000-8000-000000000004', 'SOFTWARE_TRIAL', '图形化编程软件试用', '单次 25 分钟 · 登录后可试 · 手机即可', true, 40, 'PUBLISHED'),
    ('b1000000-0000-4000-8000-000000000005', 'COURSE_TRIAL', '无人机飞行原理（试听）', '试听 2 课 · 图文预览', false, 50, 'PUBLISHED'),
    -- 草稿不应出现在列表里。
    ('b1000000-0000-4000-8000-000000000006', 'SIMULATION', '未发布的示例仿真', NULL, false, 60, 'DRAFT')
ON CONFLICT (id) DO UPDATE
SET type = EXCLUDED.type,
    title = EXCLUDED.title,
    summary = EXCLUDED.summary,
    login_required = EXCLUDED.login_required,
    sort_order = EXCLUDED.sort_order,
    status = EXCLUDED.status,
    updated_at = now();

INSERT INTO venue (id, operator_id, name, city_code, address, location, phone, opening_note, sort_order, status)
VALUES
    ('b2000000-0000-4000-8000-000000000001', 'a1000000-0000-4000-8000-000000000004',
     '东营低空与机器人体验馆', '370500', '东营区示例路 66 号',
     ST_SetSRID(ST_MakePoint(118.5823, 37.4487), 4326)::geography,
     '0546-8000 0000', '09:30–18:00 · 周一闭馆', 10, 'PUBLISHED'),
    ('b2000000-0000-4000-8000-000000000002', 'a1000000-0000-4000-8000-000000000005',
     '黄河口户外飞行营地', '370500', '黄河口示例路 8 号',
     ST_SetSRID(ST_MakePoint(118.9412, 37.7605), 4326)::geography,
     '0546-8000 0000', '周六、周日 09:00–17:00', 20, 'PUBLISHED'),
    -- 已下线的场馆：列表和详情都不应返回。
    ('b2000000-0000-4000-8000-000000000003', 'a1000000-0000-4000-8000-000000000004',
     '已下线的示例场馆', '370500', '东营区示例路 1 号', NULL, NULL, NULL, 30, 'OFFLINE')
ON CONFLICT (id) DO UPDATE
SET operator_id = EXCLUDED.operator_id,
    name = EXCLUDED.name,
    city_code = EXCLUDED.city_code,
    address = EXCLUDED.address,
    location = EXCLUDED.location,
    phone = EXCLUDED.phone,
    opening_note = EXCLUDED.opening_note,
    sort_order = EXCLUDED.sort_order,
    status = EXCLUDED.status,
    updated_at = now();

INSERT INTO venue_info_item (id, venue_id, label, content, sort_order)
VALUES
    ('b3000000-0000-4000-8000-000000000101', 'b2000000-0000-4000-8000-000000000001', '场馆展区', '模拟飞行 · 机器人编程 · 厂家展区', 10),
    ('b3000000-0000-4000-8000-000000000102', 'b2000000-0000-4000-8000-000000000001', '可承接活动', '赛事、研学、培训', 20),
    ('b3000000-0000-4000-8000-000000000103', 'b2000000-0000-4000-8000-000000000001', '到馆须知', '请提前 10 分钟到馆，出示核销码入场', 30),
    ('b3000000-0000-4000-8000-000000000104', 'b2000000-0000-4000-8000-000000000001', '入口（示例）', '东门入场，西门离场', 40),
    ('b3000000-0000-4000-8000-000000000105', 'b2000000-0000-4000-8000-000000000001', '检票（示例）', '开场前 15 分钟开始检票', 50),
    ('b3000000-0000-4000-8000-000000000106', 'b2000000-0000-4000-8000-000000000001', '迟到规则（示例）', '开场 10 分钟后停止入场', 60),
    ('b3000000-0000-4000-8000-000000000201', 'b2000000-0000-4000-8000-000000000002', '营地设施', '飞行场 · 休息区 · 充电点', 10),
    ('b3000000-0000-4000-8000-000000000202', 'b2000000-0000-4000-8000-000000000002', '可承接活动', '研学、培训、团建', 20),
    ('b3000000-0000-4000-8000-000000000203', 'b2000000-0000-4000-8000-000000000002', '到场须知', '请提前 15 分钟到营地，出示核销码入场', 30)
ON CONFLICT (id) DO UPDATE
SET venue_id = EXCLUDED.venue_id,
    label = EXCLUDED.label,
    content = EXCLUDED.content,
    sort_order = EXCLUDED.sort_order,
    updated_at = now();

INSERT INTO venue_project (id, venue_id, name, duration_minutes, min_age, capacity, limit_note, price_amount_minor, sort_order)
VALUES
    ('b4000000-0000-4000-8000-000000000101', 'b2000000-0000-4000-8000-000000000001', '机器人避障闯关', 30, 6, 8, '未满 8 岁需 1 名成人陪同', 3900, 10),
    ('b4000000-0000-4000-8000-000000000102', 'b2000000-0000-4000-8000-000000000001', '无人机模拟飞行', 30, 8, 8, '身高 120cm 以上，易晕动者慎选', 3900, 20),
    ('b4000000-0000-4000-8000-000000000103', 'b2000000-0000-4000-8000-000000000001', '编程体验课', 45, 8, 10, '需能独立操作平板电脑', 3900, 30),
    ('b4000000-0000-4000-8000-000000000201', 'b2000000-0000-4000-8000-000000000002', '真机试飞（教练陪同）', 30, 10, 6, '教练一对一带飞，需穿运动鞋', 3900, 10),
    ('b4000000-0000-4000-8000-000000000202', 'b2000000-0000-4000-8000-000000000002', '航拍体验', 20, 12, 6, '由飞手操作，体验者负责取景', 3900, 20),
    ('b4000000-0000-4000-8000-000000000203', 'b2000000-0000-4000-8000-000000000002', '营地自然课堂', 45, 6, 15, '户外活动，请自备防晒', 3900, 30)
ON CONFLICT (id) DO UPDATE
SET venue_id = EXCLUDED.venue_id,
    name = EXCLUDED.name,
    duration_minutes = EXCLUDED.duration_minutes,
    min_age = EXCLUDED.min_age,
    capacity = EXCLUDED.capacity,
    limit_note = EXCLUDED.limit_note,
    price_amount_minor = EXCLUDED.price_amount_minor,
    sort_order = EXCLUDED.sort_order,
    updated_at = now();

-- 每个项目 7 天、每天 3 场（10:00、14:00、16:00，北京时间）。编程体验课隔天开，方便看到“当天没有场次”。
-- 已约人数用天数和场次算出来，保证有可约、已满两种；当天早些的场次自然显示为已结束。
INSERT INTO venue_session (id, project_id, starts_at, ends_at, capacity, booked_count)
SELECT md5('dev-venue-session:' || p.id::text || ':' || d.day_offset || ':' || slot.hhmm)::uuid,
       p.id,
       starts.starts_at,
       starts.starts_at + make_interval(mins => p.duration_minutes),
       p.capacity,
       CASE
           WHEN slot.hhmm = '14:00' AND d.day_offset % 3 = 1 THEN p.capacity
           ELSE (d.day_offset * 2 + slot.position) % p.capacity
       END
FROM venue_project p
CROSS JOIN generate_series(0, 6) AS d(day_offset)
CROSS JOIN (VALUES ('10:00', 1), ('14:00', 3), ('16:00', 5)) AS slot(hhmm, position)
CROSS JOIN LATERAL (
    SELECT ((date_trunc('day', now() AT TIME ZONE 'Asia/Shanghai')
            + make_interval(days => d.day_offset)
            + slot.hhmm::interval) AT TIME ZONE 'Asia/Shanghai') AS starts_at
) AS starts
WHERE p.id::text LIKE 'b4000000-%'
  AND NOT (p.id = 'b4000000-0000-4000-8000-000000000103' AND d.day_offset % 2 = 1)
ON CONFLICT (id) DO UPDATE
SET project_id = EXCLUDED.project_id,
    starts_at = EXCLUDED.starts_at,
    ends_at = EXCLUDED.ends_at,
    capacity = EXCLUDED.capacity,
    booked_count = EXCLUDED.booked_count,
    updated_at = now();

INSERT INTO venue_device (id, venue_id, category, name, summary, trial_mode, sort_order)
VALUES
    ('b5000000-0000-4000-8000-000000000101', 'b2000000-0000-4000-8000-000000000001', 'ROBOT', '编程机器人探索套装', '图形化编程入门，适合 8 岁以上', 'ACCOMPANIED_TRIAL', 10),
    ('b5000000-0000-4000-8000-000000000102', 'b2000000-0000-4000-8000-000000000001', 'DRONE', '便携航拍无人机', '便携机身，体验馆可看', 'ACCOMPANIED_TRIAL', 20),
    ('b5000000-0000-4000-8000-000000000103', 'b2000000-0000-4000-8000-000000000001', 'ROBOT', '巡检机器狗', '园区巡逻与巡检演示', 'DISPLAY_ONLY', 30),
    ('b5000000-0000-4000-8000-000000000104', 'b2000000-0000-4000-8000-000000000001', 'PARTS', '无人机配件套装', '型号需确认后再下单', 'DISPLAY_ONLY', 40),
    ('b5000000-0000-4000-8000-000000000105', 'b2000000-0000-4000-8000-000000000001', 'PARTS', '避障训练场地套件', '机器人避障闯关同款场地', 'ACCOMPANIED_TRIAL', 50),
    ('b5000000-0000-4000-8000-000000000106', 'b2000000-0000-4000-8000-000000000001', 'DRONE', '行业测绘无人机', '定制配置 · 含培训 · 厂家直营', 'DISPLAY_ONLY', 60)
ON CONFLICT (id) DO UPDATE
SET venue_id = EXCLUDED.venue_id,
    category = EXCLUDED.category,
    name = EXCLUDED.name,
    summary = EXCLUDED.summary,
    trial_mode = EXCLUDED.trial_mode,
    sort_order = EXCLUDED.sort_order,
    updated_at = now();
