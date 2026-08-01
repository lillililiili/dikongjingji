CREATE TABLE inspection_service_city (
    service_id uuid NOT NULL
        REFERENCES inspection_service(id) ON DELETE RESTRICT,
    city_code varchar(16) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (service_id, city_code)
);

CREATE INDEX idx_inspection_service_city_active
    ON inspection_service_city (city_code, status, service_id);

INSERT INTO inspection_service_city (service_id, city_code)
SELECT id, city_code
FROM inspection_service
ON CONFLICT (service_id, city_code) DO NOTHING;
