-- Catalog search, portal and filter data (docs/redesign/03-App实现任务计划.md §10).
-- Existing tables keep their columns; services keep category_code / category_name as before.

CREATE TABLE inspection_category (
    code varchar(64) PRIMARY KEY,
    name varchar(64) NOT NULL,
    subtitle varchar(128),
    -- A child category is listed under its parent in the catalog (变电站 under 电力).
    parent_code varchar(64) REFERENCES inspection_category(code) ON DELETE RESTRICT,
    sort_order integer NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Reference data, the same in every environment (docs/05-data-model.md §3).
INSERT INTO inspection_category (code, name, parent_code, sort_order) VALUES
    ('POWER', '电力', NULL, 10),
    ('PHOTOVOLTAIC', '光伏', NULL, 20),
    ('OIL_GAS', '油气', NULL, 30),
    ('THERMAL', '热力', NULL, 40),
    ('PARK', '园区', NULL, 50),
    ('EQUIPMENT', '设备', NULL, 60),
    ('PERIODIC', '定期', NULL, 70);

INSERT INTO inspection_category (code, name, parent_code, sort_order) VALUES
    ('SUBSTATION', '变电站', 'POWER', 11),
    ('DISTRIBUTION', '配电网', 'POWER', 12),
    ('CABLE', '电缆', 'POWER', 13);

ALTER TABLE inspection_service
    ADD COLUMN includes_report boolean NOT NULL DEFAULT false;

CREATE TABLE inspection_service_method (
    service_id uuid NOT NULL REFERENCES inspection_service(id) ON DELETE RESTRICT,
    method_code varchar(16) NOT NULL CHECK (method_code IN ('UAV', 'ROBOT')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (service_id, method_code)
);

-- Where a service operates from in a city. The only distance rule is CENTER_POINT: the distance
-- from the user to this point (docs/05-data-model.md §8). A service without a row has no distance.
CREATE TABLE inspection_service_coverage (
    service_id uuid NOT NULL,
    city_code varchar(16) NOT NULL,
    center_point geography(Point, 4326) NOT NULL,
    distance_rule varchar(16) NOT NULL DEFAULT 'CENTER_POINT'
        CHECK (distance_rule = 'CENTER_POINT'),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (service_id, city_code),
    FOREIGN KEY (service_id, city_code)
        REFERENCES inspection_service_city(service_id, city_code) ON DELETE RESTRICT
);

CREATE INDEX idx_inspection_service_coverage_center
    ON inspection_service_coverage USING gist (center_point);

CREATE TABLE inspection_portal_content (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- NULL is the nationwide default; a city with rows of its own for a type replaces the default.
    city_code varchar(16),
    content_type varchar(16) NOT NULL
        CHECK (content_type IN ('HERO', 'GUARANTEE', 'PROCESS')),
    item_code varchar(64) NOT NULL,
    title varchar(128) NOT NULL,
    subtitle varchar(256),
    highlighted_text varchar(64),
    image_url varchar(500),
    sort_order integer NOT NULL DEFAULT 0 CHECK (sort_order >= 0),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_inspection_portal_content_item
    ON inspection_portal_content (COALESCE(city_code, ''), content_type, item_code);

CREATE INDEX idx_inspection_portal_content_lookup
    ON inspection_portal_content (content_type, status, city_code, sort_order);

-- The headline the App already shipped with, moved here so it can be changed without a release.
-- Guarantees and process steps are not inserted: nobody has confirmed that wording.
INSERT INTO inspection_portal_content (
    city_code, content_type, item_code, title, subtitle, highlighted_text, sort_order
) VALUES (
    NULL, 'HERO', 'DEFAULT', '专业巡检',
    '无人机与智能设备，为您提供安全高效的巡检服务', '隐患早发现', 0
);
