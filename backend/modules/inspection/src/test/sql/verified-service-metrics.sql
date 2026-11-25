-- Run with ON_ERROR_STOP against a migrated isolated copy with at least two
-- inspection services and one existing operator. All fixture changes roll back.
BEGIN;
SET LOCAL platform.demo_metrics TO 'false';
DO $test$
DECLARE
    service uuid; other_service uuid; operator_id uuid;
    customer uuid := gen_random_uuid(); provider uuid := gen_random_uuid();
    reservation uuid; plan uuid; service_order uuid; visit_one uuid; visit_two uuid;
    delivery_one uuid; delivery_two uuid; review uuid; retry_key uuid := gen_random_uuid();
    original_count bigint; prefix text := substr(replace(gen_random_uuid()::text, '-', ''), 1, 20);
BEGIN
    SELECT id INTO STRICT service FROM inspection_service ORDER BY id LIMIT 1;
    SELECT id INTO STRICT other_service FROM inspection_service WHERE id <> service ORDER BY id LIMIT 1;
    SELECT completed_count INTO original_count FROM inspection_service_metrics WHERE service_id = service;
    SELECT id INTO STRICT operator_id FROM identity_user ORDER BY id LIMIT 1;

    INSERT INTO inspection_reservation (
        reservation_no, customer_id, provider_id, service_id, service_title_snapshot, city_code,
        address_ciphertext, preferred_service_date, preferred_time_slot_code, contact_name_ciphertext,
        contact_mobile_ciphertext, agreement_version, created_by, updated_by)
    VALUES (prefix, customer, provider, service, 'verification', '370500', 'test', current_date,
        'MORNING', 'test', 'test', 'test', customer, customer) RETURNING id INTO reservation;
    INSERT INTO inspection_service_plan (plan_no, reservation_id, provider_id, plan_version, status,
        service_date, valid_until, total_amount_minor, created_by, updated_by)
    VALUES (prefix, reservation, provider, 1, 'CONFIRMED', current_date, now(), 0, provider, provider)
    RETURNING id INTO plan;
    INSERT INTO inspection_plan_visit (plan_id, visit_no, planned_date)
    VALUES (plan, 1, current_date), (plan, 2, current_date + 1);
    INSERT INTO inspection_service_order (service_order_no, reservation_id, plan_id, customer_id,
        provider_id, status, service_snapshot, created_by, updated_by)
    VALUES (prefix, reservation, plan, customer, provider, 'ACCEPTED', '{}', customer, customer)
    RETURNING id INTO service_order;
    INSERT INTO inspection_service_review (service_id, service_order_id, reviewer_name_snapshot,
        rating, content, reviewed_at) VALUES (service, service_order, 'customer', 4, 'review', now())
    RETURNING id INTO review;
    IF EXISTS (SELECT 1 FROM inspection_fulfilled_order WHERE service_order_id = service_order)
        OR EXISTS (SELECT 1 FROM inspection_published_review WHERE id = review) THEN
        RAISE EXCEPTION 'An order status alone must not count as acceptance';
    END IF;
    INSERT INTO inspection_service_visit (service_order_id, visit_no, planned_date, status, created_by, updated_by)
    VALUES (service_order, 1, current_date, 'ACCEPTED', customer, customer) RETURNING id INTO visit_one;
    INSERT INTO inspection_delivery (service_order_id, visit_id, status, issue_count, published_by)
    VALUES (service_order, visit_one, 'ACCEPTED', 0, provider) RETURNING id INTO delivery_one;
    INSERT INTO inspection_acceptance (service_order_id, visit_id, delivery_id, accepted_by, idempotency_key)
    VALUES (service_order, visit_one, delivery_one, customer, gen_random_uuid());
    IF EXISTS (SELECT 1 FROM inspection_fulfilled_order WHERE service_order_id = service_order) THEN
        RAISE EXCEPTION 'The second planned visit is missing';
    END IF;
    INSERT INTO inspection_service_visit (service_order_id, visit_no, planned_date, status, created_by, updated_by)
    VALUES (service_order, 2, current_date + 1, 'DELIVERED', customer, customer) RETURNING id INTO visit_two;
    INSERT INTO inspection_delivery (service_order_id, visit_id, status, issue_count, published_by)
    VALUES (service_order, visit_two, 'RETURNED', 0, provider) RETURNING id INTO delivery_two;
    IF EXISTS (SELECT 1 FROM inspection_fulfilled_order WHERE service_order_id = service_order) THEN
        RAISE EXCEPTION 'A returned second visit must not count';
    END IF;
    UPDATE inspection_service_visit SET status = 'ACCEPTED' WHERE id = visit_two;
    UPDATE inspection_delivery SET status = 'ACCEPTED' WHERE id = delivery_two;
    INSERT INTO inspection_acceptance (service_order_id, visit_id, delivery_id, accepted_by, idempotency_key)
    VALUES (service_order, visit_two, delivery_two, customer, retry_key);
    INSERT INTO inspection_acceptance (service_order_id, visit_id, delivery_id, accepted_by, idempotency_key)
    VALUES (service_order, visit_two, delivery_two, customer, retry_key) ON CONFLICT DO NOTHING;
    IF (SELECT completed_count FROM inspection_service_metrics WHERE service_id = service) <> original_count + 1
        OR NOT EXISTS (SELECT 1 FROM inspection_published_review WHERE id = review AND rating = 4) THEN
        RAISE EXCEPTION 'Expected one completed order and one public review after acceptance and replay';
    END IF;
    UPDATE inspection_service_review SET status = 'HIDDEN' WHERE id = review;
    IF EXISTS (SELECT 1 FROM inspection_published_review WHERE id = review) THEN
        RAISE EXCEPTION 'Hidden review exposed';
    END IF;
    UPDATE inspection_service_review SET status = 'ACTIVE', service_id = other_service WHERE id = review;
    IF EXISTS (SELECT 1 FROM inspection_published_review WHERE id = review) THEN
        RAISE EXCEPTION 'Review exposed under a different service';
    END IF;
    INSERT INTO inspection_catalog_change (subject_type, subject_id, action, summary, operator_id,
        operator_name, changed_at) VALUES ('SERVICE', service, 'UPDATE', 'test', operator_id, 'test', now());
    IF NOT EXISTS (SELECT 1 FROM ops_resource_origins('INSPECTION', ARRAY[service])
                   WHERE has_recorded_changes) THEN
        RAISE EXCEPTION 'Existing record lost its operator edit evidence';
    END IF;
END $test$;
ROLLBACK;
