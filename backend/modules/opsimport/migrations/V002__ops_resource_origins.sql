-- Evidence for imported prototype rows. Exact, catalogued UUIDs are paired with the
-- corresponding successful Flyway script in this database before reporting a seed origin.
CREATE TABLE ops_resource_seed_reference (
    resource_type varchar(20) NOT NULL,
    resource_id uuid NOT NULL,
    module_name varchar(24) NOT NULL,
    script_name varchar(80) NOT NULL,
    PRIMARY KEY (resource_type, resource_id)
);

INSERT INTO ops_resource_seed_reference (resource_type, module_name, script_name, resource_id)
SELECT 'INSPECTION', 'inspection', 'R__dev_inspection_catalog.sql', id::uuid
FROM (VALUES
    ('33333333-3333-3333-3333-333333333333'),
    ('44444444-4444-4444-4444-444444444444'),
    ('55555555-5555-5555-5555-555555555555'),
    ('66666666-6666-6666-6666-666666666666'),
    ('77777777-7777-7777-7777-777777777777'),
    ('88888888-8888-4888-8888-888888888888'),
    ('99999999-9999-4999-8999-999999999999'),
    ('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')) AS seed(id);

INSERT INTO ops_resource_seed_reference (resource_type, module_name, script_name, resource_id)
SELECT 'PRODUCT', 'mall', 'R__dev_mall_catalog.sql', id::uuid
FROM (VALUES
    ('c2000000-0000-4000-8000-000000000101'),
    ('c2000000-0000-4000-8000-000000000102'),
    ('c2000000-0000-4000-8000-000000000201'),
    ('c2000000-0000-4000-8000-000000000202'),
    ('c2000000-0000-4000-8000-000000000203'),
    ('c2000000-0000-4000-8000-000000000301'),
    ('c2000000-0000-4000-8000-000000000302'),
    ('c2000000-0000-4000-8000-000000000303'),
    ('c2000000-0000-4000-8000-000000000401'),
    ('c2000000-0000-4000-8000-000000000402'),
    ('c2000000-0000-4000-8000-000000000403'),
    ('c2000000-0000-4000-8000-000000000404'),
    ('c2000000-0000-4000-8000-000000000901'),
    ('c2000000-0000-4000-8000-000000000902')) AS seed(id);

INSERT INTO ops_resource_seed_reference (resource_type, module_name, script_name, resource_id)
SELECT 'ACTIVITY', 'activity', 'R__dev_activity_catalog.sql', id::uuid
FROM (VALUES
    ('a2000000-0000-4000-8000-000000000001'),
    ('a2000000-0000-4000-8000-000000000002'),
    ('a2000000-0000-4000-8000-000000000003'),
    ('a2000000-0000-4000-8000-000000000004'),
    ('a2000000-0000-4000-8000-000000000005'),
    ('a2000000-0000-4000-8000-000000000006')) AS seed(id);

INSERT INTO ops_resource_seed_reference (resource_type, module_name, script_name, resource_id)
SELECT 'COURSE', 'course', 'R__dev_course.sql', id::uuid
FROM (VALUES
    ('d7c00000-0000-4000-8000-000000000001'),
    ('d7c00000-0000-4000-8000-000000000002'),
    ('d7c00000-0000-4000-8000-000000000003'),
    ('d7c00000-0000-4000-8000-000000000004'),
    ('d7c00000-0000-4000-8000-000000000005'),
    ('d7c00000-0000-4000-8000-000000000006')) AS seed(id);

INSERT INTO ops_resource_seed_reference (resource_type, module_name, script_name, resource_id)
SELECT 'VENUE', 'experience', 'R__dev_experience_catalog.sql', id::uuid
FROM (VALUES
    ('b2000000-0000-4000-8000-000000000001'),
    ('b2000000-0000-4000-8000-000000000002'),
    ('b2000000-0000-4000-8000-000000000003')) AS seed(id);

INSERT INTO ops_resource_seed_reference (resource_type, module_name, script_name, resource_id)
SELECT 'CONTENT', 'content', 'R__dev_content.sql', id::uuid
FROM (VALUES
    ('f3000000-0000-4000-8000-000000000001'),
    ('f3000000-0000-4000-8000-000000000002'),
    ('f3000000-0000-4000-8000-000000000003'),
    ('f3000000-0000-4000-8000-000000000004'),
    ('f3000000-0000-4000-8000-000000000005'),
    ('f3000000-0000-4000-8000-000000000006')) AS seed(id);

INSERT INTO ops_resource_seed_reference (resource_type, module_name, script_name, resource_id)
SELECT 'CONTENT', 'content', 'R__dev_content_video.sql', id::uuid
FROM (VALUES
    ('f3200000-0000-4000-8000-000000000001'),
    ('f3200000-0000-4000-8000-000000000002'),
    ('f3200000-0000-4000-8000-000000000003'),
    ('f3200000-0000-4000-8000-000000000004'),
    ('f3200000-0000-4000-8000-000000000005'),
    ('f3200000-0000-4000-8000-000000000006')) AS seed(id);

INSERT INTO ops_resource_seed_reference (resource_type, module_name, script_name, resource_id)
SELECT 'TOPIC', 'content', 'R__dev_content.sql', id::uuid
FROM (VALUES
    ('f3100000-0000-4000-8000-000000000001')) AS seed(id);

INSERT INTO ops_resource_seed_reference (resource_type, module_name, script_name, resource_id)
SELECT 'PRO_SERVICE', 'proservice', 'R__dev_proservice_catalog.sql', id::uuid
FROM (VALUES
    ('f2100000-0000-4000-8000-000000000001'),
    ('f2100000-0000-4000-8000-000000000002'),
    ('f2100000-0000-4000-8000-000000000003'),
    ('f2100000-0000-4000-8000-000000000004'),
    ('f2100000-0000-4000-8000-000000000005'),
    ('f2100000-0000-4000-8000-000000000006'),
    ('f2100000-0000-4000-8000-000000000007'),
    ('f2100000-0000-4000-8000-000000000008'),
    ('f2100000-0000-4000-8000-000000000009'),
    ('f2100000-0000-4000-8000-000000000010'),
    ('f2100000-0000-4000-8000-000000000011'),
    ('f2100000-0000-4000-8000-000000000012'),
    ('f2100000-0000-4000-8000-000000000013'),
    ('f2100000-0000-4000-8000-000000000014'),
    ('f2100000-0000-4000-8000-000000000015'),
    ('f2100000-0000-4000-8000-000000000016')) AS seed(id);

INSERT INTO ops_resource_seed_reference (resource_type, module_name, script_name, resource_id)
SELECT 'LIVE', 'live', 'R__dev_live.sql', id::uuid
FROM (VALUES
    ('f4000000-0000-4000-8000-000000000001'),
    ('f4000000-0000-4000-8000-000000000002'),
    ('f4000000-0000-4000-8000-000000000003'),
    ('f4000000-0000-4000-8000-000000000004'),
    ('f4000000-0000-4000-8000-000000000005'),
    ('f4000000-0000-4000-8000-000000000006')) AS seed(id);

CREATE FUNCTION ops_seed_applied(module_name text, script_name text) RETURNS boolean
LANGUAGE plpgsql STABLE AS $$
DECLARE
    history_table text := 'flyway_history_' || module_name || '_development';
    applied boolean;
BEGIN
    IF to_regclass(format('%I', history_table)) IS NULL THEN RETURN false; END IF;
    EXECUTE format('SELECT EXISTS (SELECT 1 FROM %I WHERE success AND script = $1)', history_table)
        INTO applied USING script_name;
    RETURN applied;
END $$;

CREATE INDEX idx_ops_import_change_target ON ops_import_change(ref_kind, target_id, change_type);

-- Each module keeps its own immutable operation log. This read function resolves its
-- tables at call time so that the modules can migrate in their existing order.
CREATE FUNCTION ops_resource_origins(kind text, requested_ids uuid[])
RETURNS TABLE (resource_id uuid, origin text, has_recorded_changes boolean)
LANGUAGE plpgsql STABLE AS $$
DECLARE
    cfg record;
BEGIN
    SELECT * INTO STRICT cfg FROM (VALUES
      ('ACTIVITY','activity','activity_ops_change','activity_id','CREATE','true','false','a.subject_type = ''ACTIVITY'''),
      ('VENUE','venue','venue_ops_change','venue_id','CREATE','true','false','a.subject_type = ''VENUE'''),
      ('PRODUCT','mall_product','mall_catalog_operation','product_id','PRODUCT_CREATE','a.action <> ''IMPORT''','r.ops_create_key IS NOT NULL','true'),
      ('COURSE','course','course_ops_change','course_id','CREATE','true','false','true'),
      ('CONTENT','content_article','content_ops_change','subject_id','CREATE','a.subject_type IN (''ARTICLE'',''VIDEO'')','false','true'),
      ('TOPIC','content_topic','content_ops_change','subject_id','CREATE','a.subject_type = ''TOPIC''','false','true'),
      ('PRO_SERVICE','pro_service','pro_service_change','service_id','CREATE','true','false','true'),
      ('INSPECTION','inspection_service','inspection_catalog_change','subject_id','CREATE','a.subject_type = ''SERVICE''','false','true'),
      ('LIVE','live_room','live_ops_change','room_id','CREATE','true','r.idempotency_key IS NOT NULL','true')
    ) AS t(resource_type, resource_table, audit_table, audit_key, create_action, audit_filter, has_creation_key, top_level)
    WHERE t.resource_type = kind;

    RETURN QUERY EXECUTE format($q$
        SELECT r.id, CASE
            WHEN seed.resource_id IS NOT NULL AND ops_seed_applied(seed.module_name, seed.script_name) THEN 'DEVELOPMENT_SEED'
            WHEN log.created OR imports.created OR %s THEN 'RECORDED_CREATION'
            ELSE 'UNVERIFIED' END,
            coalesce(log.changed, false) OR coalesce(imports.changed, false)
        FROM %I r
        LEFT JOIN ops_resource_seed_reference seed ON seed.resource_type = $1 AND seed.resource_id = r.id
        LEFT JOIN LATERAL (
            SELECT bool_or(i.change_type = 'CREATED') created, bool_or(i.change_type = 'UPDATED') changed
            FROM ops_import_change i WHERE i.ref_kind = $1 AND i.target_id = r.id
        ) imports ON true
        LEFT JOIN LATERAL (
            SELECT bool_or(a.action = $3 AND %s) created, bool_or(NOT (a.action = $3 AND %s)) changed
            FROM %I a WHERE a.%I = r.id AND %s
        ) log ON true
        WHERE $2 IS NULL OR r.id = ANY($2)
        ORDER BY r.id
    $q$, cfg.has_creation_key, cfg.resource_table, cfg.top_level, cfg.top_level, cfg.audit_table, cfg.audit_key, cfg.audit_filter)
    USING kind, requested_ids, cfg.create_action;
END $$;
