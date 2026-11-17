-- Experience catalog: online experience items, venues, their projects, sessions and devices on show.
-- Read-only in this version; venue bookings come with the booking step.
-- identity_organization, pgcrypto and postgis are created by the identity and inspection migrations, which run first.
CREATE TABLE experience_item (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    type varchar(24) NOT NULL
        CHECK (type IN ('SIMULATION', 'RECORDED_DEMO', 'SOFTWARE_TRIAL', 'COURSE_TRIAL')),
    title varchar(120) NOT NULL,
    summary varchar(200),
    cover_url varchar(500),
    login_required boolean NOT NULL DEFAULT false,
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'OFFLINE', 'DELETED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_experience_item_published ON experience_item (status, type, sort_order, id);

CREATE TABLE venue (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    operator_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    name varchar(120) NOT NULL,
    cover_url varchar(500),
    city_code varchar(16) NOT NULL,
    address varchar(240) NOT NULL,
    location geography(Point, 4326),
    phone varchar(32),
    opening_note varchar(120),
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'OFFLINE', 'DELETED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_venue_city_published ON venue (city_code, status, sort_order, id);

CREATE TABLE venue_info_item (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    venue_id uuid NOT NULL REFERENCES venue(id) ON DELETE RESTRICT,
    label varchar(40) NOT NULL,
    content varchar(200) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_venue_info_item_venue ON venue_info_item (venue_id, status, sort_order, id);

CREATE TABLE venue_project (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    venue_id uuid NOT NULL REFERENCES venue(id) ON DELETE RESTRICT,
    name varchar(120) NOT NULL,
    duration_minutes integer NOT NULL CHECK (duration_minutes > 0),
    min_age smallint CHECK (min_age >= 0),
    capacity integer NOT NULL CHECK (capacity > 0),
    limit_note varchar(200),
    price_amount_minor bigint NOT NULL CHECK (price_amount_minor >= 0),
    currency char(3) NOT NULL DEFAULT 'CNY' CHECK (currency = 'CNY'),
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_venue_project_venue ON venue_project (venue_id, status, sort_order, id);

CREATE TABLE venue_session (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id uuid NOT NULL REFERENCES venue_project(id) ON DELETE RESTRICT,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    capacity integer NOT NULL CHECK (capacity > 0),
    booked_count integer NOT NULL DEFAULT 0 CHECK (booked_count >= 0),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CANCELLED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_venue_session_seats CHECK (booked_count <= capacity),
    CONSTRAINT ck_venue_session_end CHECK (ends_at > starts_at)
);

CREATE INDEX idx_venue_session_project_time ON venue_session (project_id, status, starts_at);

CREATE TABLE venue_device (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    venue_id uuid NOT NULL REFERENCES venue(id) ON DELETE RESTRICT,
    category varchar(16) NOT NULL CHECK (category IN ('ROBOT', 'DRONE', 'PARTS')),
    name varchar(120) NOT NULL,
    summary varchar(200),
    image_url varchar(500),
    trial_mode varchar(24) NOT NULL
        CHECK (trial_mode IN ('ACCOMPANIED_TRIAL', 'DISPLAY_ONLY')),
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_venue_device_venue ON venue_device (venue_id, status, category, sort_order, id);
