CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE inspection_reservation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_no varchar(32) NOT NULL UNIQUE,
    customer_id uuid NOT NULL,
    customer_organization_id uuid,
    provider_id uuid,
    service_id uuid NOT NULL,
    service_title_snapshot varchar(160) NOT NULL,
    service_cover_url_snapshot varchar(500),
    city_code varchar(16) NOT NULL,
    address_ciphertext bytea NOT NULL,
    location geography(Point, 4326),
    line_length_meters bigint CHECK (line_length_meters IS NULL OR line_length_meters >= 0),
    preferred_service_date date NOT NULL,
    note_ciphertext bytea,
    contact_name_ciphertext bytea NOT NULL,
    contact_mobile_ciphertext bytea NOT NULL,
    agreement_version varchar(32) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'SUBMITTED'
        CHECK (status IN ('SUBMITTED', 'PLAN_PUBLISHED', 'CANCELLED')),
    submitted_at timestamptz NOT NULL DEFAULT now(),
    cancelled_at timestamptz,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_inspection_reservation_customer
    ON inspection_reservation (customer_id, submitted_at DESC);
CREATE INDEX idx_inspection_reservation_provider_status
    ON inspection_reservation (provider_id, status, submitted_at DESC);
CREATE INDEX idx_inspection_reservation_city_status
    ON inspection_reservation (city_code, status);
CREATE INDEX idx_inspection_reservation_location
    ON inspection_reservation USING gist (location);

CREATE TABLE inspection_reservation_item (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    reservation_id uuid NOT NULL REFERENCES inspection_reservation(id) ON DELETE RESTRICT,
    item_code varchar(64) NOT NULL,
    item_name_snapshot varchar(120) NOT NULL,
    display_order integer NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (reservation_id, item_code)
);

CREATE TABLE inspection_file_object (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    purpose varchar(32) NOT NULL
        CHECK (purpose IN ('RESERVATION_ATTACHMENT', 'DELIVERY_ARTIFACT', 'DELIVERY_BUNDLE')),
    owner_id uuid NOT NULL,
    provider_id uuid,
    bucket_name varchar(63) NOT NULL,
    object_key varchar(512) NOT NULL,
    original_name varchar(180) NOT NULL,
    content_type varchar(120) NOT NULL,
    extension varchar(16) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes > 0),
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[a-f0-9]{64}$'),
    object_etag varchar(160),
    file_status varchar(24) NOT NULL DEFAULT 'PENDING_UPLOAD'
        CHECK (file_status IN (
            'PENDING_UPLOAD',
            'UPLOADED',
            'SCANNING',
            'READY',
            'REJECTED',
            'DELETED'
        )),
    scan_engine varchar(80),
    scan_rule_version varchar(80),
    scan_failure_reason varchar(500),
    uploaded_by uuid NOT NULL,
    uploaded_at timestamptz,
    scanned_at timestamptz,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (bucket_name, object_key)
);

CREATE INDEX idx_inspection_file_owner
    ON inspection_file_object (owner_id, purpose, file_status);
CREATE INDEX idx_inspection_file_scan
    ON inspection_file_object (file_status, created_at);

CREATE TABLE inspection_reservation_attachment (
    reservation_id uuid NOT NULL REFERENCES inspection_reservation(id) ON DELETE RESTRICT,
    file_id uuid NOT NULL REFERENCES inspection_file_object(id) ON DELETE RESTRICT,
    display_order integer NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (reservation_id, file_id)
);

CREATE TABLE inspection_service_plan (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_no varchar(32) NOT NULL UNIQUE,
    reservation_id uuid NOT NULL REFERENCES inspection_reservation(id) ON DELETE RESTRICT,
    provider_id uuid NOT NULL,
    plan_version integer NOT NULL CHECK (plan_version > 0),
    status varchar(16) NOT NULL
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'CONFIRMED', 'EXPIRED', 'WITHDRAWN')),
    summary varchar(1000),
    service_date date NOT NULL,
    valid_until timestamptz NOT NULL,
    currency char(3) NOT NULL DEFAULT 'CNY' CHECK (currency = 'CNY'),
    total_amount_minor bigint NOT NULL CHECK (total_amount_minor >= 0),
    published_at timestamptz,
    confirmed_at timestamptz,
    agreement_version varchar(32),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (reservation_id, plan_version)
);

CREATE UNIQUE INDEX uk_inspection_plan_current
    ON inspection_service_plan (reservation_id)
    WHERE status IN ('PUBLISHED', 'CONFIRMED');

CREATE INDEX idx_inspection_plan_expiration
    ON inspection_service_plan (status, valid_until)
    WHERE status = 'PUBLISHED';

CREATE TABLE inspection_service_plan_item (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id uuid NOT NULL REFERENCES inspection_service_plan(id) ON DELETE RESTRICT,
    item_code varchar(64) NOT NULL,
    item_name varchar(120) NOT NULL,
    quantity integer NOT NULL CHECK (quantity > 0),
    unit varchar(24) NOT NULL,
    unit_price_minor bigint NOT NULL CHECK (unit_price_minor >= 0),
    line_amount_minor bigint NOT NULL CHECK (line_amount_minor >= 0),
    display_order integer NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (plan_id, item_code)
);

CREATE TABLE inspection_plan_deliverable (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id uuid NOT NULL REFERENCES inspection_service_plan(id) ON DELETE RESTRICT,
    deliverable_code varchar(64) NOT NULL,
    deliverable_name varchar(120) NOT NULL,
    display_order integer NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (plan_id, deliverable_code)
);

CREATE TABLE inspection_service_order (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    service_order_no varchar(32) NOT NULL UNIQUE,
    reservation_id uuid NOT NULL UNIQUE REFERENCES inspection_reservation(id) ON DELETE RESTRICT,
    plan_id uuid NOT NULL UNIQUE REFERENCES inspection_service_plan(id) ON DELETE RESTRICT,
    customer_id uuid NOT NULL,
    provider_id uuid NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ARRANGING'
        CHECK (status IN ('ARRANGING', 'IN_PROGRESS', 'DELIVERED', 'ACCEPTED')),
    service_snapshot jsonb NOT NULL,
    planned_start_at timestamptz,
    started_at timestamptz,
    delivered_at timestamptz,
    accepted_at timestamptz,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_by uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_inspection_order_customer
    ON inspection_service_order (customer_id, created_at DESC);
CREATE INDEX idx_inspection_order_provider_status
    ON inspection_service_order (provider_id, status, updated_at DESC);

CREATE TABLE inspection_order_assignment (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    service_order_id uuid NOT NULL REFERENCES inspection_service_order(id) ON DELETE RESTRICT,
    assignment_type varchar(20) NOT NULL
        CHECK (assignment_type IN ('TEAM_MEMBER', 'DEVICE')),
    resource_id uuid NOT NULL,
    display_name_snapshot varchar(120) NOT NULL,
    role_label varchar(80),
    assigned_by uuid NOT NULL,
    assigned_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (service_order_id, assignment_type, resource_id)
);

CREATE TABLE inspection_order_progress (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    service_order_id uuid NOT NULL REFERENCES inspection_service_order(id) ON DELETE RESTRICT,
    event_code varchar(32) NOT NULL
        CHECK (event_code IN (
            'ARRANGEMENT_READY',
            'SERVICE_STARTED',
            'SERVICE_PROGRESS',
            'SERVICE_COMPLETED'
        )),
    occurred_at timestamptz NOT NULL,
    message varchar(500) NOT NULL,
    operator_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_inspection_order_progress_timeline
    ON inspection_order_progress (service_order_id, occurred_at, id);

CREATE TABLE inspection_delivery (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    service_order_id uuid NOT NULL UNIQUE REFERENCES inspection_service_order(id) ON DELETE RESTRICT,
    status varchar(16) NOT NULL DEFAULT 'PUBLISHED'
        CHECK (status IN ('PUBLISHED', 'ACCEPTED')),
    tower_count integer NOT NULL CHECK (tower_count >= 0),
    inspected_length_meters bigint NOT NULL CHECK (inspected_length_meters >= 0),
    issue_count integer NOT NULL CHECK (issue_count >= 0),
    issue_summary jsonb NOT NULL DEFAULT '[]'::jsonb,
    bundle_file_id uuid REFERENCES inspection_file_object(id) ON DELETE RESTRICT,
    published_by uuid NOT NULL,
    published_at timestamptz NOT NULL DEFAULT now(),
    accepted_at timestamptz,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE inspection_delivery_file (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_id uuid NOT NULL REFERENCES inspection_delivery(id) ON DELETE RESTRICT,
    file_id uuid NOT NULL REFERENCES inspection_file_object(id) ON DELETE RESTRICT,
    file_type varchar(24) NOT NULL
        CHECK (file_type IN ('IMAGE_ARCHIVE', 'DEFECT_LIST', 'POINT_KML', 'INSPECTION_REPORT')),
    display_name varchar(120) NOT NULL,
    point_count integer CHECK (point_count IS NULL OR point_count >= 0),
    display_order integer NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (delivery_id, file_id),
    UNIQUE (delivery_id, file_type)
);

CREATE TABLE inspection_acceptance (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    service_order_id uuid NOT NULL UNIQUE REFERENCES inspection_service_order(id) ON DELETE RESTRICT,
    delivery_id uuid NOT NULL UNIQUE REFERENCES inspection_delivery(id) ON DELETE RESTRICT,
    accepted_by uuid NOT NULL,
    accepted_at timestamptz NOT NULL DEFAULT now(),
    note_ciphertext bytea,
    idempotency_key uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (accepted_by, idempotency_key)
);

CREATE TABLE inspection_state_transition (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type varchar(24) NOT NULL
        CHECK (aggregate_type IN ('RESERVATION', 'PLAN', 'SERVICE_ORDER', 'DELIVERY')),
    aggregate_id uuid NOT NULL,
    from_status varchar(32),
    to_status varchar(32) NOT NULL,
    actor_type varchar(24) NOT NULL
        CHECK (actor_type IN ('CUSTOMER', 'PROVIDER_OPERATOR', 'PLATFORM_OPERATOR', 'SYSTEM')),
    actor_id uuid,
    reason_code varchar(80),
    request_id varchar(80) NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_inspection_transition_aggregate
    ON inspection_state_transition (aggregate_type, aggregate_id, occurred_at, id);

CREATE TABLE inspection_idempotency_record (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id uuid NOT NULL,
    operation_code varchar(80) NOT NULL,
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL CHECK (request_hash ~ '^[a-f0-9]{64}$'),
    resource_type varchar(32),
    resource_id uuid,
    response_status integer,
    response_body jsonb,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (actor_id, operation_code, idempotency_key)
);

CREATE INDEX idx_inspection_idempotency_expiry
    ON inspection_idempotency_record (expires_at);

CREATE TABLE inspection_domain_event_outbox (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type varchar(32) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(80) NOT NULL,
    event_payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error varchar(500)
);

CREATE INDEX idx_inspection_outbox_pending
    ON inspection_domain_event_outbox (next_attempt_at, occurred_at)
    WHERE published_at IS NULL;

