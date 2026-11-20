INSERT INTO identity_organization (
    id,
    organization_code,
    organization_name,
    organization_type,
    status
) VALUES (
    '22222222-2222-2222-2222-222222222222',
    'DEV-PROVIDER-YUNYI',
    '云翼科技',
    'PROVIDER',
    'ACTIVE'
)
ON CONFLICT (id) DO UPDATE
SET organization_name = EXCLUDED.organization_name,
    organization_type = EXCLUDED.organization_type,
    status = EXCLUDED.status,
    updated_at = now();

INSERT INTO inspection_provider_profile (
    provider_id,
    logo_asset_code,
    certification_label,
    team_description,
    status
) VALUES (
    '22222222-2222-2222-2222-222222222222',
    'FEIXIANG_APP_ICON',
    '企业认证',
    '专业飞手团队',
    'ACTIVE'
)
ON CONFLICT (provider_id) DO UPDATE
SET logo_url = NULL,
    logo_asset_code = EXCLUDED.logo_asset_code,
    certification_label = EXCLUDED.certification_label,
    team_description = EXCLUDED.team_description,
    status = EXCLUDED.status,
    updated_at = now();

-- 开发种子（仅 dev profile 的 flyway_history_inspection_development 加载）：
-- 电力线路巡检的计价方式与取消说明，文案照 2026-11-14 修订版原型 InspDetail 画板（2026-11-20）。
UPDATE inspection_service
SET price_note = '按公里计价，现场评估后以方案报价为准',
    cancellation_note = '方案确认前取消不收费，确认后按方案约定',
    completed_count = 8560,
    updated_at = now()
WHERE id = '33333333-3333-3333-3333-333333333333';

-- 服务内容就是预约页“巡检项目”的选项；名称照 InspDetail 画板，编码不变（预约冒烟脚本按编码提交）。
INSERT INTO inspection_service_booking_item (
    service_id,
    item_code,
    item_label,
    description,
    icon_key,
    display_order,
    status
) VALUES
    (
        '33333333-3333-3333-3333-333333333333',
        'POWER_LINE',
        '杆塔本体巡检',
        '巡检杆塔本体',
        'tower',
        10,
        'ACTIVE'
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'TOWER',
        '导线地线巡检',
        '巡检导线和地线',
        'power-line',
        20,
        'ACTIVE'
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'INSULATOR_FITTINGS',
        '绝缘子及金具巡检',
        '巡检绝缘子及金具',
        'insulator',
        30,
        'ACTIVE'
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'CORRIDOR_ENV',
        '通道环境巡检',
        '巡检线路通道环境',
        'shield',
        40,
        'ACTIVE'
    )
ON CONFLICT (service_id, item_code) DO UPDATE
SET item_label = EXCLUDED.item_label,
    description = EXCLUDED.description,
    icon_key = EXCLUDED.icon_key,
    display_order = EXCLUDED.display_order,
    status = EXCLUDED.status,
    updated_at = now();

-- 2026-11-20 原型对齐：交付成果照 InspDetail 画板（高清巡检影像、缺陷清单、点位标注、巡检报告），
-- 原来的“热成像数据”换成“点位标注”，旧行删掉，免得详情页出现五项。
DELETE FROM inspection_service_detail_item
WHERE service_id = '33333333-3333-3333-3333-333333333333'
  AND section_type = 'DELIVERABLE'
  AND item_code = 'THERMAL';

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
) VALUES
    (
        '33333333-3333-3333-3333-333333333333',
        'CAPABILITY',
        'MODELING',
        '高清影像',
        NULL,
        'checkbox',
        NULL,
        10,
        'ACTIVE'
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'CAPABILITY',
        'AI_DEFECT',
        '缺陷标注',
        NULL,
        'checkbox',
        NULL,
        20,
        'ACTIVE'
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'CAPABILITY',
        'REPORT',
        '巡检报告',
        NULL,
        'checkbox',
        NULL,
        30,
        'ACTIVE'
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'PROCESS',
        'REQUEST',
        '提交需求',
        NULL,
        NULL,
        NULL,
        10,
        'ACTIVE'
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'PROCESS',
        'CONFIRM',
        '方案确认',
        NULL,
        NULL,
        NULL,
        20,
        'ACTIVE'
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'PROCESS',
        'INSPECTION',
        '现场巡检',
        NULL,
        NULL,
        NULL,
        30,
        'ACTIVE'
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'PROCESS',
        'DELIVERY',
        '报告交付',
        NULL,
        NULL,
        NULL,
        40,
        'ACTIVE'
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'DELIVERABLE',
        'IMAGES',
        '高清巡检影像',
        NULL,
        'image',
        'POWER_LINE_IMAGE',
        10,
        'ACTIVE'
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'DELIVERABLE',
        'POINTS',
        '点位标注',
        NULL,
        'image',
        NULL,
        25,
        'ACTIVE'
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'DELIVERABLE',
        'DEFECT_LIST',
        '缺陷清单',
        NULL,
        'list',
        'DEFECT_LIST_IMAGE',
        20,
        'ACTIVE'
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'DELIVERABLE',
        'REPORT',
        '巡检报告',
        NULL,
        'compose',
        'REPORT_COVER_IMAGE',
        40,
        'ACTIVE'
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'GUARANTEE',
        'PILOT',
        '专业飞手',
        NULL,
        'staff',
        NULL,
        10,
        'ACTIVE'
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'GUARANTEE',
        'COMPLIANCE',
        '合规作业',
        NULL,
        'auth',
        NULL,
        20,
        'ACTIVE'
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'GUARANTEE',
        'SECURITY',
        '数据保密',
        NULL,
        'locked',
        NULL,
        30,
        'ACTIVE'
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'PRICE_FACTOR',
        'LINE_LENGTH',
        '线路长度',
        NULL,
        NULL,
        NULL,
        10,
        'ACTIVE'
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'PRICE_FACTOR',
        'TOWER_COUNT',
        '杆塔数量',
        NULL,
        NULL,
        NULL,
        20,
        'ACTIVE'
    ),
    (
        '33333333-3333-3333-3333-333333333333',
        'PRICE_FACTOR',
        'SITE_CONDITION',
        '现场条件',
        NULL,
        NULL,
        NULL,
        30,
        'ACTIVE'
    )
ON CONFLICT (service_id, section_type, item_code) DO UPDATE
SET title = EXCLUDED.title,
    description = EXCLUDED.description,
    icon_key = EXCLUDED.icon_key,
    image_url = NULL,
    image_asset_code = EXCLUDED.image_asset_code,
    display_order = EXCLUDED.display_order,
    status = EXCLUDED.status,
    updated_at = now();

INSERT INTO inspection_service_review (
    id,
    service_id,
    reviewer_name_snapshot,
    rating,
    content,
    reviewed_at,
    status
)
SELECT
    md5('dev-power-review-' || series_no::text)::uuid,
    '33333333-3333-3333-3333-333333333333'::uuid,
    CASE series_no
        WHEN 1 THEN '李**'
        WHEN 2 THEN '王**'
        ELSE '用户**'
    END,
    CASE
        WHEN series_no <= 295 THEN 5
        WHEN series_no <= 322 THEN 4
        ELSE 3
    END,
    CASE series_no
        WHEN 1 THEN '飞手很专业，现场沟通清楚，当天完成巡检。报告中的缺陷和点位标注得很清楚。'
        WHEN 2 THEN '响应及时，巡检影像清晰，后续整改使用很方便。'
        ELSE '服务专业，报告清晰，交付及时。'
    END,
    timestamptz '2026-07-27 09:00:00+08'
        - make_interval(days => series_no - 1),
    'ACTIVE'
FROM generate_series(1, 328) AS series_no
ON CONFLICT (id) DO UPDATE
SET reviewer_name_snapshot = EXCLUDED.reviewer_name_snapshot,
    rating = EXCLUDED.rating,
    content = EXCLUDED.content,
    reviewed_at = EXCLUDED.reviewed_at,
    status = EXCLUDED.status,
    updated_at = now();

INSERT INTO inspection_service_review_tag (
    review_id,
    tag_code,
    tag_label
)
SELECT
    md5('dev-power-review-' || series_no::text)::uuid,
    tag_code,
    tag_label
FROM generate_series(1, 328) AS series_no
CROSS JOIN LATERAL (
    VALUES
        ('PROFESSIONAL', '巡检专业', 126),
        ('CLEAR_REPORT', '报告清晰', 98),
        ('ON_TIME', '响应及时', 87),
        ('ACCURATE', '定位准确', 61)
) AS tags(tag_code, tag_label, max_series)
WHERE series_no <= max_series
ON CONFLICT (review_id, tag_code) DO UPDATE
SET tag_label = EXCLUDED.tag_label;

INSERT INTO inspection_service_review_image (
    review_id,
    image_asset_code,
    display_order,
    status
) VALUES
    (
        md5('dev-power-review-1')::uuid,
        'POWER_LINE_IMAGE',
        10,
        'ACTIVE'
    ),
    (
        md5('dev-power-review-1')::uuid,
        'THERMAL_IMAGE',
        20,
        'ACTIVE'
    ),
    (
        md5('dev-power-review-1')::uuid,
        'INSPECTION_HERO_IMAGE',
        30,
        'ACTIVE'
    )
ON CONFLICT (review_id, display_order) DO UPDATE
SET image_url = NULL,
    image_asset_code = EXCLUDED.image_asset_code,
    status = EXCLUDED.status,
    updated_at = now();
