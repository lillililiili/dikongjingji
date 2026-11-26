-- The normal connection reads only business evidence. The opt-in demonstration
-- database can still reproduce the original prototype without changing a row.
CREATE FUNCTION platform_demo_metrics() RETURNS boolean
LANGUAGE sql STABLE PARALLEL SAFE AS $$
    SELECT coalesce(current_setting('platform.demo_metrics', true) = 'true', false)
$$;

CREATE VIEW inspection_fulfilled_order AS
SELECT o.id AS service_order_id, r.service_id
FROM inspection_service_order o
JOIN inspection_reservation r ON r.id = o.reservation_id
WHERE o.status = 'ACCEPTED'
  AND EXISTS (SELECT 1 FROM inspection_plan_visit p WHERE p.plan_id = o.plan_id)
  AND NOT EXISTS (
      SELECT 1 FROM inspection_plan_visit p WHERE p.plan_id = o.plan_id AND NOT EXISTS (
          SELECT 1 FROM inspection_service_visit v WHERE v.service_order_id = o.id AND v.visit_no = p.visit_no
      )
  )
  AND NOT EXISTS (
      SELECT 1 FROM inspection_service_visit v
      WHERE v.service_order_id = o.id AND (
          v.status <> 'ACCEPTED' OR NOT EXISTS (
              SELECT 1 FROM inspection_acceptance a
              JOIN inspection_delivery d ON d.id = a.delivery_id AND d.visit_id = v.id
                  AND d.service_order_id = o.id AND d.status = 'ACCEPTED'
              WHERE a.visit_id = v.id AND a.service_order_id = o.id AND a.accepted_by = o.customer_id
          )
      )
  );

CREATE INDEX idx_inspection_fulfilled_order_reservation
    ON inspection_service_order (reservation_id) WHERE status = 'ACCEPTED';
CREATE INDEX idx_inspection_reservation_service ON inspection_reservation (service_id, id);

CREATE VIEW inspection_service_metrics AS
SELECT s.id AS service_id,
       (CASE WHEN platform_demo_metrics() THEN s.completed_count ELSE 0 END)
           + (SELECT count(*) FROM inspection_fulfilled_order o WHERE o.service_id = s.id) AS completed_count
FROM inspection_service s;

CREATE VIEW inspection_published_review AS
SELECT r.*
FROM inspection_service_review r
WHERE r.status = 'ACTIVE' AND (platform_demo_metrics() OR EXISTS (
    SELECT 1 FROM inspection_fulfilled_order o
    WHERE o.service_order_id = r.service_order_id AND o.service_id = r.service_id
));

-- Keep the original popularity totals. Recover the observed increments above the
-- last known initialization only in a database where that script actually ran.
ALTER TABLE platform_city ADD COLUMN recorded_search_count bigint NOT NULL DEFAULT 0
    CHECK (recorded_search_count >= 0);
UPDATE platform_city SET recorded_search_count = search_count;
DO $$
BEGIN
    IF to_regclass('flyway_history_inspection_development') IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM flyway_history_inspection_development
                   WHERE success AND script = 'R__dev_platform_city_popularity.sql') THEN
            UPDATE platform_city c
            SET recorded_search_count = greatest(0, c.search_count - b.initial_count)
            FROM (VALUES ('370100',1260), ('370200',1120), ('370500',980),
                         ('370600',760), ('370700',690), ('371300',610)) b(city_code,initial_count)
            WHERE c.city_code = b.city_code;
        END IF;
    END IF;
END $$;
