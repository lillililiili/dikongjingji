-- 开发种子（仅 dev profile 的 flyway_history_inspection_development 加载，生产不会执行），2026-11-20。
-- 原型对齐（docs/redesign/06 §3 R3）：电力以外的巡检服务补上详情页要用的数据，
-- 文案照 2026-11-14 修订版原型的 InspDetailHeat / InspDetailOil / InspDetailSolar / InspDetailPark 画板：
-- 计价方式、影响价格、取消说明、三项能力、四步服务流程、交付成果和服务保障。
-- 按项目报价的园区服务（7777…）故意不写 price_note，列表卡仍由服务端派生“方案确认后定价”。
-- 取消说明只是展示文案，能否取消仍由服务流程状态机决定。

UPDATE inspection_service service
SET price_note = dev.price_note,
    cancellation_note = '方案确认前取消不收费，确认后按方案约定',
    updated_at = now()
FROM (
    VALUES
        ('44444444-4444-4444-4444-444444444444'::uuid, '按次计价，含热成像检测和报告'),
        ('55555555-5555-5555-5555-555555555555'::uuid, '按次计价，含管线巡查和报告'),
        ('66666666-6666-6666-6666-666666666666'::uuid, '按次计价，含组件检测和报告')
) dev(service_id, price_note)
WHERE service.id = dev.service_id;

UPDATE inspection_service
SET cancellation_note = '方案确认前取消不收费，确认后按方案约定',
    updated_at = now()
WHERE id = '77777777-7777-7777-7777-777777777777';

INSERT INTO inspection_service_detail_item (
    service_id,
    section_type,
    item_code,
    title,
    description,
    icon_key,
    image_asset_code,
    display_order,
    status
)
SELECT service_id, section_type, item_code, title, NULL, icon_key, NULL, display_order, 'ACTIVE'
FROM (
    VALUES
        -- 热力管网巡检
        ('44444444-4444-4444-4444-444444444444'::uuid, 'CAPABILITY', 'THERMAL_IMAGES', '热成像影像', 'checkbox', 10),
        ('44444444-4444-4444-4444-444444444444'::uuid, 'CAPABILITY', 'LEAK_MARKS', '泄漏标注', 'checkbox', 20),
        ('44444444-4444-4444-4444-444444444444'::uuid, 'CAPABILITY', 'NETWORK_ASSESSMENT', '管网评估', 'checkbox', 30),
        ('44444444-4444-4444-4444-444444444444'::uuid, 'PRICE_FACTOR', 'NETWORK_LENGTH', '管网长度', NULL, 10),
        ('44444444-4444-4444-4444-444444444444'::uuid, 'PRICE_FACTOR', 'MANHOLE_COUNT', '检查井数量', NULL, 20),
        ('44444444-4444-4444-4444-444444444444'::uuid, 'PRICE_FACTOR', 'DETECTION_WINDOW', '检测时段', NULL, 30),
        -- 燃气管道巡检
        ('55555555-5555-5555-5555-555555555555'::uuid, 'CAPABILITY', 'VISIBLE_INFRARED', '可见光与红外', 'checkbox', 10),
        ('55555555-5555-5555-5555-555555555555'::uuid, 'CAPABILITY', 'LEAK_MARKS', '泄漏标注', 'checkbox', 20),
        ('55555555-5555-5555-5555-555555555555'::uuid, 'CAPABILITY', 'SAFETY_REPORT', '安全报告', 'checkbox', 30),
        ('55555555-5555-5555-5555-555555555555'::uuid, 'PRICE_FACTOR', 'PIPELINE_LENGTH', '管线长度', NULL, 10),
        ('55555555-5555-5555-5555-555555555555'::uuid, 'PRICE_FACTOR', 'STATION_COUNT', '站场数量', NULL, 20),
        ('55555555-5555-5555-5555-555555555555'::uuid, 'PRICE_FACTOR', 'SITE_CONDITION', '现场条件', NULL, 30),
        -- 光伏电站巡检
        ('66666666-6666-6666-6666-666666666666'::uuid, 'CAPABILITY', 'THERMAL_IMAGES', '热成像影像', 'checkbox', 10),
        ('66666666-6666-6666-6666-666666666666'::uuid, 'CAPABILITY', 'DEFECT_MARKS', '缺陷标注', 'checkbox', 20),
        ('66666666-6666-6666-6666-666666666666'::uuid, 'CAPABILITY', 'OM_REPORT', '运维报告', 'checkbox', 30),
        ('66666666-6666-6666-6666-666666666666'::uuid, 'PRICE_FACTOR', 'CAPACITY', '装机容量', NULL, 10),
        ('66666666-6666-6666-6666-666666666666'::uuid, 'PRICE_FACTOR', 'MODULE_COUNT', '组件数量', NULL, 20),
        ('66666666-6666-6666-6666-666666666666'::uuid, 'PRICE_FACTOR', 'TERRAIN', '场站地形', NULL, 30),
        -- 园区综合巡检（按项目报价）
        ('77777777-7777-7777-7777-777777777777'::uuid, 'CAPABILITY', 'UAV', '无人机', 'checkbox', 10),
        ('77777777-7777-7777-7777-777777777777'::uuid, 'CAPABILITY', 'ROBOT_DOG', '机器狗', 'checkbox', 20),
        ('77777777-7777-7777-7777-777777777777'::uuid, 'CAPABILITY', 'REPORT', '巡检报告', 'checkbox', 30),
        ('77777777-7777-7777-7777-777777777777'::uuid, 'PRICE_FACTOR', 'PARK_AREA', '园区面积', NULL, 10),
        ('77777777-7777-7777-7777-777777777777'::uuid, 'PRICE_FACTOR', 'FREQUENCY', '巡检频次', NULL, 20),
        ('77777777-7777-7777-7777-777777777777'::uuid, 'PRICE_FACTOR', 'EQUIPMENT_MIX', '设备组合', NULL, 30),
        ('77777777-7777-7777-7777-777777777777'::uuid, 'DELIVERABLE', 'REPORT', '巡检报告', 'compose', 10),
        ('77777777-7777-7777-7777-777777777777'::uuid, 'DELIVERABLE', 'HAZARD_LIST', '隐患清单', 'list', 20),
        ('77777777-7777-7777-7777-777777777777'::uuid, 'DELIVERABLE', 'IMAGE_ARCHIVE', '影像存档', 'image', 30)
) dev(service_id, section_type, item_code, title, icon_key, display_order)
UNION ALL
-- 四个服务共用的服务流程、交付成果（园区除外）和服务保障，和电力线路巡检一致。
SELECT service.service_id, common.section_type, common.item_code, common.title, NULL, common.icon_key, NULL, common.display_order, 'ACTIVE'
FROM (
    VALUES
        ('44444444-4444-4444-4444-444444444444'::uuid, true),
        ('55555555-5555-5555-5555-555555555555'::uuid, true),
        ('66666666-6666-6666-6666-666666666666'::uuid, true),
        ('77777777-7777-7777-7777-777777777777'::uuid, false)
) service(service_id, standard_files)
CROSS JOIN (
    VALUES
        ('PROCESS', 'REQUEST', '提交需求', NULL, 10, false),
        ('PROCESS', 'CONFIRM', '方案确认', NULL, 20, false),
        ('PROCESS', 'INSPECTION', '现场巡检', NULL, 30, false),
        ('PROCESS', 'DELIVERY', '报告交付', NULL, 40, false),
        ('DELIVERABLE', 'IMAGES', '高清巡检影像', 'image', 10, true),
        ('DELIVERABLE', 'DEFECT_LIST', '缺陷清单', 'list', 20, true),
        ('DELIVERABLE', 'POINTS', '点位标注', 'image', 30, true),
        ('DELIVERABLE', 'REPORT', '巡检报告', 'compose', 40, true),
        ('GUARANTEE', 'PILOT', '专业飞手', 'staff', 10, false),
        ('GUARANTEE', 'COMPLIANCE', '合规作业', 'auth', 20, false),
        ('GUARANTEE', 'SECURITY', '数据保密', 'locked', 30, false)
) common(section_type, item_code, title, icon_key, display_order, files_only)
WHERE service.standard_files OR NOT common.files_only
ON CONFLICT (service_id, section_type, item_code) DO UPDATE
SET title = EXCLUDED.title,
    description = EXCLUDED.description,
    icon_key = EXCLUDED.icon_key,
    image_url = NULL,
    image_asset_code = EXCLUDED.image_asset_code,
    display_order = EXCLUDED.display_order,
    status = EXCLUDED.status,
    updated_at = now();
