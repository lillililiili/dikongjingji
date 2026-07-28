CREATE TABLE inspection_service (
    id uuid PRIMARY KEY,
    provider_id uuid NOT NULL,
    title varchar(160) NOT NULL,
    cover_url varchar(500),
    city_code varchar(16) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_inspection_service_active_city
    ON inspection_service (city_code, status, id);
