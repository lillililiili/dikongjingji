-- Activity catalog: contests, training, study tours and open days with their sessions.
-- Read-only in this version; enrolment tables come with the enrolment step.
-- identity_organization, pgcrypto and postgis are created by the identity and inspection migrations, which run first.
CREATE TABLE activity (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organizer_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    co_organizer_id uuid REFERENCES identity_organization(id) ON DELETE RESTRICT,
    type varchar(16) NOT NULL
        CHECK (type IN ('CONTEST', 'TRAINING', 'STUDY_TOUR', 'OPEN_DAY')),
    title varchar(160) NOT NULL,
    subtitle varchar(200),
    cover_url varchar(500),
    city_code varchar(16) NOT NULL,
    venue_name varchar(120) NOT NULL,
    venue_address varchar(240) NOT NULL,
    venue_location geography(Point, 4326),
    schedule_note varchar(120),
    price_mode varchar(16) NOT NULL
        CHECK (price_mode IN ('PER_PERSON', 'FREE', 'INQUIRY')),
    price_amount_minor bigint CHECK (price_amount_minor >= 0),
    currency char(3) NOT NULL DEFAULT 'CNY' CHECK (currency = 'CNY'),
    min_age smallint CHECK (min_age >= 0),
    max_age smallint CHECK (max_age >= 0),
    eligibility_note varchar(300),
    seat_note varchar(300),
    refund_note varchar(500),
    waitlist_allowed boolean NOT NULL DEFAULT false,
    team_enrollment_allowed boolean NOT NULL DEFAULT false,
    status varchar(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'OFFLINE', 'DELETED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_activity_price_matches_mode
        CHECK ((price_mode = 'PER_PERSON') = (price_amount_minor IS NOT NULL)),
    CONSTRAINT ck_activity_age_range
        CHECK (min_age IS NULL OR max_age IS NULL OR min_age <= max_age)
);

CREATE INDEX idx_activity_city_published ON activity (city_code, status, type, id);

CREATE TABLE activity_session (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    activity_id uuid NOT NULL REFERENCES activity(id) ON DELETE RESTRICT,
    label varchar(60),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz,
    check_in_opens_at timestamptz,
    enrollment_closes_at timestamptz NOT NULL,
    capacity integer NOT NULL CHECK (capacity > 0),
    enrolled_count integer NOT NULL DEFAULT 0 CHECK (enrolled_count >= 0),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'CANCELLED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_activity_session_seats CHECK (enrolled_count <= capacity),
    CONSTRAINT ck_activity_session_deadline CHECK (enrollment_closes_at <= starts_at),
    CONSTRAINT ck_activity_session_end CHECK (ends_at IS NULL OR ends_at > starts_at)
);

CREATE INDEX idx_activity_session_upcoming
    ON activity_session (activity_id, status, starts_at);
