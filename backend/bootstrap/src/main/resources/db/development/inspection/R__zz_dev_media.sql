-- 仅将已有原型图片关联到指定的开发记录，未提供素材的服务保持空图。
UPDATE inspection_service AS s
SET cover_url = '/api/v1/dev-media/images/inspection/' || sample.filename
FROM (VALUES
    ('INS-SVC-DEV-POWER', 'service-power-line.webp'),
    ('INS-SVC-DEV-THERMAL', 'category-thermal.webp'),
    ('INS-SVC-DEV-OIL-GAS', 'service-oil-gas.webp'),
    ('INS-SVC-DEV-PHOTOVOLTAIC', 'service-photovoltaic.webp'),
    ('INS-SVC-DEV-PARK', 'service-park.webp')) AS sample(service_no, filename)
WHERE s.service_no = sample.service_no AND s.cover_url IS NULL;

UPDATE inspection_portal_content
SET image_url = '/api/v1/dev-media/images/inspection/inspection-hero-v1.png'
WHERE city_code IS NULL AND content_type = 'HERO' AND item_code = 'DEFAULT' AND image_url IS NULL;

UPDATE inspection_service_detail_item AS d
SET image_url = '/api/v1/dev-media/images/inspection/' || sample.filename
FROM (VALUES
    ('IMAGES', 'service-power-line.webp'),
    ('DEFECT_LIST', 'detail-defect-list.png'),
    ('REPORT', 'detail-report-cover.png')) AS sample(item_code, filename)
WHERE d.service_id = '33333333-3333-3333-3333-333333333333'
  AND d.section_type = 'DELIVERABLE' AND d.item_code = sample.item_code AND d.image_url IS NULL;

UPDATE inspection_service_review_image AS r
SET image_url = '/api/v1/dev-media/images/inspection/' || sample.filename
FROM (VALUES
    (10, 'service-power-line.webp'),
    (20, 'category-thermal.webp'),
    (30, 'inspection-hero-v1.png')) AS sample(display_order, filename)
WHERE r.review_id = md5('dev-power-review-1')::uuid
  AND r.display_order = sample.display_order AND r.image_url IS NULL;
