ALTER TABLE inspection_service
    ADD COLUMN service_no varchar(64),
    ADD COLUMN category_code varchar(64),
    ADD COLUMN category_name varchar(80),
    ADD COLUMN summary varchar(500),
    ADD COLUMN price_mode varchar(24)
        CHECK (price_mode IN (
            'STARTING_PRICE',
            'PER_DISTANCE',
            'PER_VISIT',
            'PROJECT_QUOTE'
        )),
    ADD COLUMN starting_amount_minor bigint
        CHECK (starting_amount_minor IS NULL OR starting_amount_minor >= 0),
    ADD COLUMN currency char(3)
        CHECK (currency IS NULL OR currency = 'CNY'),
    ADD COLUMN unit_code varchar(32),
    ADD COLUMN unit_label varchar(40),
    ADD COLUMN availability varchar(24)
        CHECK (availability IN ('AVAILABLE', 'RESERVABLE', 'UNAVAILABLE')),
    ADD COLUMN primary_action varchar(24)
        CHECK (primary_action IN ('CONSULT', 'RESERVE', 'REQUEST_QUOTE', 'NONE'));

CREATE TABLE inspection_service_booking_item (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id uuid NOT NULL REFERENCES inspection_service(id) ON DELETE RESTRICT,
    item_code varchar(64) NOT NULL,
    item_label varchar(80) NOT NULL,
    display_order integer NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    UNIQUE (service_id, item_code)
);

CREATE INDEX idx_inspection_service_booking_item_active
    ON inspection_service_booking_item (service_id, status, display_order, id);
