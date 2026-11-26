CREATE TABLE platform_map_place (
    city_code varchar(16) NOT NULL REFERENCES platform_city(city_code),
    place_key varchar(80) NOT NULL,
    name varchar(120) NOT NULL,
    address varchar(240) NOT NULL,
    location geography(Point, 4326) NOT NULL,
    sample boolean NOT NULL DEFAULT false,
    published boolean NOT NULL DEFAULT false,
    sort_order integer NOT NULL DEFAULT 0,
    PRIMARY KEY (city_code, place_key)
);

CREATE INDEX idx_platform_map_place_published
    ON platform_map_place (city_code, sort_order, place_key) WHERE published;
