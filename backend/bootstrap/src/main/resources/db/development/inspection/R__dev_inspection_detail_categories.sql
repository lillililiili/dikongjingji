-- 开发种子（仅 dev profile 的 flyway_history_inspection_development 加载，生产不会执行），2026-11-20。
-- 原型对齐（docs/redesign/06 §3 R3）：电力以外的巡检服务补上详情页要用的数据，
-- 文案照 2026-11-14 修订版原型的 InspDetailHeat / InspDetailOil / InspDetailSolar / InspDetailPark 画板：
-- 计价方式、影响价格、取消说明、三项能力、四项服务内容（预约项目）、服务流程、交付成果和服务保障。
-- 2026-11-20 起园区（7777…）也写 price_note（InspDetailPark 的计价方式）；服务端的列表卡优先用配置的
-- 文案，所以园区的列表卡也跟着显示这句，不再是派生的“方案确认后定价”。
-- 取消说明只是展示文案，能否取消仍由服务流程状态机决定。

UPDATE inspection_service service
SET price_note = dev.price_note,
    cancellation_note = '方案确认前取消不收费，确认后按方案约定',
    updated_at = now()
FROM (
    VALUES
        ('44444444-4444-4444-4444-444444444444'::uuid, '按次计价，按管网长度现场评估后以方案报价为准'),
        ('55555555-5555-5555-5555-555555555555'::uuid, '按次计价，按管线长度现场评估后以方案报价为准'),
        ('66666666-6666-6666-6666-666666666666'::uuid, '按次计价，按场站规模现场评估后以方案报价为准'),
        ('77777777-7777-7777-7777-777777777777'::uuid, '方案确认后定价')
) dev(service_id, price_note)
WHERE service.id = dev.service_id;

-- 服务内容就是预约页“巡检项目”的选项，照各画板补到四项。原有编码能对上的改名沿用，
-- 对不上的停用（预约单存的是项目名快照，停用不影响已有预约）。
-- 注意：R__dev_inspection_catalog.sql 先插入了这几个服务的三项；那个文件单独重跑时会把名称和状态改回去，
-- 改它时要连这里一起看。
INSERT INTO inspection_service_booking_item (
    service_id,
    item_code,
    item_label,
    display_order,
    status
)
SELECT service_id, item_code, item_label, display_order, 'ACTIVE'
FROM (
    VALUES
        -- 热力管网巡检（InspDetailHeat）
        ('44444444-4444-4444-4444-444444444444'::uuid, 'THERMAL_IMAGING', '管网热成像巡查', 10),
        ('44444444-4444-4444-4444-444444444444'::uuid, 'LEAKAGE', '泄漏点识别', 20),
        ('44444444-4444-4444-4444-444444444444'::uuid, 'MANHOLE', '检查井巡视', 30),
        ('44444444-4444-4444-4444-444444444444'::uuid, 'NETWORK_ASSESSMENT', '保温层评估', 40),
        -- 燃气管道巡检（InspDetailOil）
        ('55555555-5555-5555-5555-555555555555'::uuid, 'PIPELINE_CORRIDOR', '管线走廊巡查', 10),
        ('55555555-5555-5555-5555-555555555555'::uuid, 'LEAK_IDENTIFICATION', '泄漏与渗漏排查', 20),
        ('55555555-5555-5555-5555-555555555555'::uuid, 'THIRD_PARTY_WORKS', '第三方施工巡查', 30),
        ('55555555-5555-5555-5555-555555555555'::uuid, 'STATION_EQUIPMENT', '站场设备巡检', 40),
        -- 光伏电站巡检（InspDetailSolar）
        ('66666666-6666-6666-6666-666666666666'::uuid, 'HOT_SPOT', '组件热斑检测', 10),
        ('66666666-6666-6666-6666-666666666666'::uuid, 'COMBINER_INVERTER', '汇流箱与逆变器检查', 20),
        ('66666666-6666-6666-6666-666666666666'::uuid, 'BRACKET_CABLE', '支架与线缆巡检', 30),
        ('66666666-6666-6666-6666-666666666666'::uuid, 'SITE_ENVIRONMENT', '场站环境巡查', 40),
        -- 园区综合巡检（InspDetailPark）
        ('77777777-7777-7777-7777-777777777777'::uuid, 'SECURITY', '周界巡查', 10),
        ('77777777-7777-7777-7777-777777777777'::uuid, 'NIGHT_PATROL', '夜间巡逻', 20),
        ('77777777-7777-7777-7777-777777777777'::uuid, 'KEY_AREA', '重点区域巡查', 30),
        ('77777777-7777-7777-7777-777777777777'::uuid, 'HAZARD', '隐患排查', 40)
) dev(service_id, item_code, item_label, display_order)
ON CONFLICT (service_id, item_code) DO UPDATE
SET item_label = EXCLUDED.item_label,
    display_order = EXCLUDED.display_order,
    status = 'ACTIVE',
    updated_at = now();

UPDATE inspection_service_booking_item
SET status = 'DISABLED',
    updated_at = now()
WHERE (service_id, item_code) IN (
    ('55555555-5555-5555-5555-555555555555'::uuid, 'GAS_DETECTION'),
    ('55555555-5555-5555-5555-555555555555'::uuid, 'SAFETY_ASSESSMENT'),
    ('66666666-6666-6666-6666-666666666666'::uuid, 'MODULE'),
    ('66666666-6666-6666-6666-666666666666'::uuid, 'EFFICIENCY'),
    ('77777777-7777-7777-7777-777777777777'::uuid, 'EQUIPMENT'),
    ('77777777-7777-7777-7777-777777777777'::uuid, 'ENVIRONMENT')
);

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
        ('77777777-7777-7777-7777-777777777777'::uuid, 'DELIVERABLE', 'IMAGE_ARCHIVE', '影像存档', 'image', 30),
        -- 园区走需求发布、报价比选，流程五步照 InspDetailPark；画板流程下那行付款说明不做（巡检付款节点未定）。
        ('77777777-7777-7777-7777-777777777777'::uuid, 'PROCESS', 'REQUEST', '发布需求', NULL, 10),
        ('77777777-7777-7777-7777-777777777777'::uuid, 'PROCESS', 'QUOTE_COMPARE', '报价比选', NULL, 20),
        ('77777777-7777-7777-7777-777777777777'::uuid, 'PROCESS', 'CONFIRM', '方案确认', NULL, 30),
        ('77777777-7777-7777-7777-777777777777'::uuid, 'PROCESS', 'INSPECTION', '现场巡检', NULL, 40),
        ('77777777-7777-7777-7777-777777777777'::uuid, 'PROCESS', 'DELIVERY', '报告验收', NULL, 50)
) dev(service_id, section_type, item_code, title, icon_key, display_order)
UNION ALL
-- 热力、燃气、光伏共用的四步服务流程和交付成果，四个服务共用的服务保障，和电力线路巡检一致。
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
        ('PROCESS', 'REQUEST', '提交需求', NULL, 10, true),
        ('PROCESS', 'CONFIRM', '方案确认', NULL, 20, true),
        ('PROCESS', 'INSPECTION', '现场巡检', NULL, 30, true),
        ('PROCESS', 'DELIVERY', '报告交付', NULL, 40, true),
        ('DELIVERABLE', 'IMAGES', '高清巡检影像', 'image', 10, true),
        ('DELIVERABLE', 'DEFECT_LIST', '缺陷清单', 'list', 20, true),
        ('DELIVERABLE', 'POINTS', '点位标注', 'image', 30, true),
        ('DELIVERABLE', 'REPORT', '巡检报告', 'compose', 40, true),
        ('GUARANTEE', 'PILOT', '专业飞手', 'staff', 10, false),
        ('GUARANTEE', 'COMPLIANCE', '合规作业', 'auth', 20, false),
        ('GUARANTEE', 'SECURITY', '数据保密', 'locked', 30, false)
) common(section_type, item_code, title, icon_key, display_order, standard_only)
-- 园区的流程和交付成果在上面单列，这里只给它服务保障。
WHERE service.standard_files OR NOT common.standard_only
ON CONFLICT (service_id, section_type, item_code) DO UPDATE
SET title = EXCLUDED.title,
    description = EXCLUDED.description,
    icon_key = EXCLUDED.icon_key,
    image_url = NULL,
    image_asset_code = EXCLUDED.image_asset_code,
    display_order = EXCLUDED.display_order,
    status = EXCLUDED.status,
    updated_at = now();
