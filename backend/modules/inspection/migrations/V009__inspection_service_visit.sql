-- 多次巡检：服务单下增加巡检次，排期、进度、交付和验收移到巡检次上。
-- 存量服务单回填为 1 次巡检，巡检次 ID 直接用服务单 ID，这样派生的交付 ID、
-- 文件 owner_id 和对象键都不用改。

CREATE TABLE inspection_plan_visit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id uuid NOT NULL REFERENCES inspection_service_plan(id) ON DELETE RESTRICT,
    visit_no integer NOT NULL CHECK (visit_no > 0),
    planned_date date NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (plan_id, visit_no)
);

CREATE TABLE inspection_service_visit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    service_order_id uuid NOT NULL REFERENCES inspection_service_order(id) ON DELETE RESTRICT,
    visit_no integer NOT NULL CHECK (visit_no > 0),
    planned_date date NOT NULL,
    planned_start_at timestamptz,
    status varchar(20) NOT NULL DEFAULT 'PENDING_SCHEDULE'
        CHECK (status IN (
            'PENDING_SCHEDULE',
            'SCHEDULED',
            'IN_PROGRESS',
            'DELIVERED',
            'ACCEPTED'
        )),
    started_at timestamptz,
    delivered_at timestamptz,
    accepted_at timestamptz,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (service_order_id, visit_no)
);

CREATE INDEX idx_inspection_visit_order_status
    ON inspection_service_visit (service_order_id, status, visit_no);

-- 方案上的付款节点是金额快照；服务单上的节点额外记录交易模块回写的结果。
CREATE TABLE inspection_plan_payment_node (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id uuid NOT NULL REFERENCES inspection_service_plan(id) ON DELETE RESTRICT,
    node_no integer NOT NULL CHECK (node_no > 0),
    node_type varchar(16) NOT NULL
        CHECK (node_type IN ('DOWN_PAYMENT', 'PER_VISIT')),
    visit_no integer CHECK (visit_no IS NULL OR visit_no > 0),
    amount_minor bigint NOT NULL CHECK (amount_minor >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (plan_id, node_no),
    CHECK ((node_type = 'DOWN_PAYMENT') = (visit_no IS NULL))
);

CREATE TABLE inspection_order_payment_node (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    service_order_id uuid NOT NULL REFERENCES inspection_service_order(id) ON DELETE RESTRICT,
    node_no integer NOT NULL CHECK (node_no > 0),
    node_type varchar(16) NOT NULL
        CHECK (node_type IN ('DOWN_PAYMENT', 'PER_VISIT')),
    visit_id uuid REFERENCES inspection_service_visit(id) ON DELETE RESTRICT,
    amount_minor bigint NOT NULL CHECK (amount_minor >= 0),
    trade_payment_id uuid,
    paid_at timestamptz,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (service_order_id, node_no),
    CHECK ((node_type = 'DOWN_PAYMENT') = (visit_id IS NULL))
);

ALTER TABLE inspection_service_plan
    ADD COLUMN scope_summary varchar(120),
    ADD COLUMN service_methods jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN report_due_hours integer
        CHECK (report_due_hours IS NULL OR report_due_hours > 0),
    ADD COLUMN media_retention_days integer
        CHECK (media_retention_days IS NULL OR media_retention_days > 0),
    ADD COLUMN change_note varchar(200),
    ADD COLUMN confirmed_by uuid;

UPDATE inspection_service_plan
SET confirmed_by = updated_by
WHERE status = 'CONFIRMED';

ALTER TABLE inspection_plan_deliverable
    ADD COLUMN file_type varchar(24)
        CHECK (file_type IS NULL OR file_type IN (
            'IMAGE_ARCHIVE',
            'DEFECT_LIST',
            'POINT_KML',
            'INSPECTION_REPORT'
        ));

ALTER TABLE inspection_service_order
    ADD COLUMN payment_mode varchar(8) NOT NULL DEFAULT 'NONE'
        CHECK (payment_mode IN ('NONE', 'NODES'));

ALTER TABLE inspection_order_assignment
    ADD COLUMN contact_mobile_ciphertext bytea,
    ADD COLUMN contact_mobile_masked varchar(16);

INSERT INTO inspection_plan_visit (plan_id, visit_no, planned_date, created_at)
SELECT id, 1, service_date, created_at
FROM inspection_service_plan;

INSERT INTO inspection_service_visit (
    id, service_order_id, visit_no, planned_date, planned_start_at, status,
    started_at, delivered_at, accepted_at, version,
    created_by, created_at, updated_by, updated_at
)
SELECT
    o.id, o.id, 1, p.service_date, o.planned_start_at,
    CASE
        WHEN o.status <> 'ARRANGING' THEN o.status
        WHEN o.planned_start_at IS NULL THEN 'PENDING_SCHEDULE'
        ELSE 'SCHEDULED'
    END,
    o.started_at, o.delivered_at, o.accepted_at, 0,
    o.created_by, o.created_at, o.updated_by, o.updated_at
FROM inspection_service_order o
JOIN inspection_service_plan p ON p.id = o.plan_id;

ALTER TABLE inspection_service_order
    DROP COLUMN planned_start_at;

ALTER TABLE inspection_order_progress
    ADD COLUMN visit_id uuid REFERENCES inspection_service_visit(id) ON DELETE RESTRICT;

UPDATE inspection_order_progress
SET visit_id = service_order_id
WHERE event_code <> 'ARRANGEMENT_READY';

CREATE INDEX idx_inspection_order_progress_visit
    ON inspection_order_progress (visit_id, occurred_at, id)
    WHERE visit_id IS NOT NULL;

ALTER TABLE inspection_delivery
    ADD COLUMN visit_id uuid REFERENCES inspection_service_visit(id) ON DELETE RESTRICT,
    ADD COLUMN metrics jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN issue_group_by varchar(16) NOT NULL DEFAULT 'SEVERITY'
        CHECK (issue_group_by IN ('SEVERITY', 'CATEGORY')),
    ALTER COLUMN tower_count DROP NOT NULL,
    ALTER COLUMN inspected_length_meters DROP NOT NULL;

UPDATE inspection_delivery
SET visit_id = service_order_id;

UPDATE inspection_delivery d
SET issue_summary = (
    SELECT COALESCE(jsonb_agg(jsonb_build_object(
        'groupCode', item->>'severityCode',
        'groupName', item->>'severityName',
        'count', CAST(item->>'count' AS integer)
    )), '[]'::jsonb)
    FROM jsonb_array_elements(d.issue_summary) item
);

ALTER TABLE inspection_delivery
    ALTER COLUMN visit_id SET NOT NULL,
    DROP CONSTRAINT inspection_delivery_service_order_id_key,
    ADD CONSTRAINT uk_inspection_delivery_visit UNIQUE (visit_id);

CREATE INDEX idx_inspection_delivery_order
    ON inspection_delivery (service_order_id);

ALTER TABLE inspection_delivery_file
    ADD COLUMN item_count integer
        CHECK (item_count IS NULL OR item_count >= 0);

ALTER TABLE inspection_acceptance
    ADD COLUMN visit_id uuid REFERENCES inspection_service_visit(id) ON DELETE RESTRICT;

UPDATE inspection_acceptance
SET visit_id = service_order_id;

ALTER TABLE inspection_acceptance
    ALTER COLUMN visit_id SET NOT NULL,
    DROP CONSTRAINT inspection_acceptance_service_order_id_key,
    ADD CONSTRAINT uk_inspection_acceptance_visit UNIQUE (visit_id);

CREATE INDEX idx_inspection_acceptance_order
    ON inspection_acceptance (service_order_id);

ALTER TABLE inspection_state_transition
    DROP CONSTRAINT inspection_state_transition_aggregate_type_check,
    ADD CONSTRAINT inspection_state_transition_aggregate_type_check
        CHECK (aggregate_type IN (
            'RESERVATION',
            'PLAN',
            'SERVICE_ORDER',
            'SERVICE_VISIT',
            'DELIVERY'
        ));
