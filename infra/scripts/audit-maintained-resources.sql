-- Run on the restored copy with psql -v ON_ERROR_STOP=1 -f <this file>.
-- Each row retains the original business ID. A NULL reference count means that
-- these order/enrollment modules have no direct reference to that resource type.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;

WITH resource_types(kind) AS (
    VALUES ('PRODUCT'), ('ACTIVITY'), ('VENUE'), ('COURSE'), ('INSPECTION'),
           ('CONTENT'), ('TOPIC'), ('PRO_SERVICE'), ('LIVE')
), product_orders AS (
    SELECT product_id, order_id FROM trade_order_line
    UNION
    SELECT i.product_id, o.trade_order_id FROM rental_order o JOIN rental_inquiry i ON i.id = o.inquiry_id
), linked(kind, resource_id, reference_count) AS (
    SELECT 'PRODUCT', product_id, count(*) FROM product_orders GROUP BY product_id
    UNION ALL SELECT 'ACTIVITY', activity_id, count(*) FROM activity_enrollment GROUP BY activity_id
    UNION ALL SELECT 'VENUE', venue_id, count(*) FROM venue_booking GROUP BY venue_id
    UNION ALL SELECT 'COURSE', course_id, count(*) FROM course_enrollment GROUP BY course_id
    UNION ALL SELECT 'INSPECTION', service_id, count(*) FROM inspection_reservation GROUP BY service_id
)
SELECT t.kind, o.resource_id, o.origin, o.has_recorded_changes,
       CASE WHEN t.kind IN ('PRODUCT', 'ACTIVITY', 'VENUE', 'COURSE', 'INSPECTION')
            THEN coalesce(l.reference_count, 0) END AS order_or_enrollment_references
FROM resource_types t
CROSS JOIN LATERAL ops_resource_origins(t.kind, NULL::uuid[]) o
LEFT JOIN linked l ON l.kind = t.kind AND l.resource_id = o.resource_id
ORDER BY t.kind, o.resource_id;

SELECT s.id AS service_id, s.title, s.completed_count AS historical_completed_count,
       (SELECT count(*) FROM inspection_fulfilled_order o WHERE o.service_id = s.id) AS verified_completed_count,
       (SELECT count(*) FROM inspection_service_review r WHERE r.service_id = s.id AND r.status = 'ACTIVE')
           AS historical_visible_reviews,
       (SELECT count(*) FROM inspection_service_review r
        JOIN inspection_fulfilled_order o ON o.service_order_id = r.service_order_id AND o.service_id = r.service_id
        WHERE r.service_id = s.id AND r.status = 'ACTIVE') AS verified_visible_reviews
FROM inspection_service s ORDER BY s.id;

SELECT id AS room_id, viewer_count, recorded_viewer_count, like_count, recorded_like_count,
       comment_count, recorded_comment_count FROM live_room ORDER BY id;
SELECT city_code, city_name, search_count, recorded_search_count FROM platform_city
WHERE search_count > 0 OR recorded_search_count > 0 ORDER BY city_code;
COMMIT;
