CREATE TABLE platform_city (
    city_code varchar(16) PRIMARY KEY,
    city_name varchar(80) NOT NULL,
    city_pinyin varchar(160) NOT NULL,
    city_initials varchar(32) NOT NULL,
    province_code varchar(16) NOT NULL,
    province_name varchar(80) NOT NULL,
    province_pinyin varchar(160) NOT NULL,
    province_initials varchar(32) NOT NULL,
    search_count bigint NOT NULL DEFAULT 0 CHECK (search_count >= 0),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_platform_city_tree
    ON platform_city (
        status,
        province_initials,
        province_pinyin,
        city_initials,
        city_pinyin,
        city_code
    );

CREATE INDEX idx_platform_city_hot
    ON platform_city (status, search_count DESC, city_pinyin, city_code);
