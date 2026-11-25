-- Run on a migrated isolated database with a platform operator. Fixtures roll back.
BEGIN;
DO $test$
DECLARE
    actor uuid; product uuid; batch uuid;
BEGIN
    SELECT id INTO STRICT actor FROM identity_user ORDER BY id LIMIT 1;
    INSERT INTO mall_product (category, name, sale_mode) VALUES ('PARTS', 'test import', 'BUY')
    RETURNING id INTO product;
    IF NOT EXISTS (SELECT 1 FROM ops_resource_origins('PRODUCT', ARRAY[product])
                   WHERE origin = 'UNVERIFIED' AND NOT has_recorded_changes) THEN
        RAISE EXCEPTION 'Unattributed records require verification';
    END IF;
    INSERT INTO ops_import_batch (kind,status,file_sha256,row_count,issue_count,checked_by,checked_by_name,
        checked_at,retained_until,imported_by,imported_by_name,imported_at)
    VALUES ('MALL','IMPORTED',encode(digest(product::text,'sha256'),'hex'),1,0,actor,'test',now(),
        now() + interval '180 days',actor,'test',now()) RETURNING id INTO batch;
    INSERT INTO ops_import_change (batch_id, sort_order, ref_kind, reference, target_id, change_type)
    VALUES (batch, 1, 'PRODUCT', 'fixture', product, 'CREATED');
    IF NOT EXISTS (SELECT 1 FROM ops_resource_origins('PRODUCT', ARRAY[product])
                   WHERE origin = 'RECORDED_CREATION' AND NOT has_recorded_changes) THEN
        RAISE EXCEPTION 'First import must establish origin';
    END IF;
    INSERT INTO ops_import_change (batch_id, sort_order, ref_kind, reference, target_id, change_type)
    VALUES (batch, 2, 'PRODUCT', 'fixture', product, 'UPDATED');
    IF NOT EXISTS (SELECT 1 FROM ops_resource_origins('PRODUCT', ARRAY[product])
                   WHERE origin = 'RECORDED_CREATION' AND has_recorded_changes) THEN
        RAISE EXCEPTION 'Subsequent import must establish edit evidence';
    END IF;
END $test$;
ROLLBACK;
