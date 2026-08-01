ALTER TABLE inspection_service_booking_item
    ADD COLUMN description varchar(240),
    ADD COLUMN icon_key varchar(64);

ALTER TABLE inspection_service
    ADD COLUMN price_note varchar(240);

CREATE TABLE inspection_provider_profile (
    provider_id uuid PRIMARY KEY
        REFERENCES identity_organization(id) ON DELETE RESTRICT,
    logo_url varchar(500),
    logo_asset_code varchar(80),
    certification_label varchar(80),
    team_description varchar(240),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (logo_url IS NOT NULL OR logo_asset_code IS NOT NULL)
);

CREATE TABLE inspection_service_detail_item (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id uuid NOT NULL
        REFERENCES inspection_service(id) ON DELETE RESTRICT,
    section_type varchar(24) NOT NULL
        CHECK (section_type IN ('CAPABILITY', 'PROCESS', 'DELIVERABLE', 'GUARANTEE')),
    item_code varchar(64) NOT NULL,
    title varchar(120) NOT NULL,
    description varchar(240),
    icon_key varchar(64),
    image_url varchar(500),
    image_asset_code varchar(80),
    display_order integer NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (service_id, section_type, item_code)
);

CREATE INDEX idx_inspection_service_detail_item_active
    ON inspection_service_detail_item (
        service_id,
        section_type,
        status,
        display_order,
        id
    );

CREATE TABLE inspection_service_review (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id uuid NOT NULL
        REFERENCES inspection_service(id) ON DELETE RESTRICT,
    service_order_id uuid
        REFERENCES inspection_service_order(id) ON DELETE RESTRICT,
    reviewer_name_snapshot varchar(80) NOT NULL,
    rating smallint NOT NULL CHECK (rating BETWEEN 1 AND 5),
    content varchar(2000) NOT NULL,
    reviewed_at timestamptz NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'HIDDEN', 'DELETED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uk_inspection_service_review_order
    ON inspection_service_review (service_order_id)
    WHERE service_order_id IS NOT NULL;

CREATE INDEX idx_inspection_service_review_active
    ON inspection_service_review (service_id, status, reviewed_at DESC, id);

CREATE TABLE inspection_service_review_tag (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id uuid NOT NULL
        REFERENCES inspection_service_review(id) ON DELETE RESTRICT,
    tag_code varchar(64) NOT NULL,
    tag_label varchar(80) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (review_id, tag_code)
);

CREATE INDEX idx_inspection_service_review_tag_review
    ON inspection_service_review_tag (review_id, tag_code);

CREATE TABLE inspection_service_review_image (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id uuid NOT NULL
        REFERENCES inspection_service_review(id) ON DELETE RESTRICT,
    image_url varchar(500),
    image_asset_code varchar(80),
    display_order integer NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (image_url IS NOT NULL OR image_asset_code IS NOT NULL),
    UNIQUE (review_id, display_order)
);

CREATE INDEX idx_inspection_service_review_image_active
    ON inspection_service_review_image (review_id, status, display_order, id);
