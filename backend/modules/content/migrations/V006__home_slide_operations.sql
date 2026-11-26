ALTER TABLE content_home_slide
    ADD COLUMN version integer NOT NULL DEFAULT 0 CHECK (version >= 0);

CREATE TABLE content_home_slide_change (
    city_code varchar(16) NOT NULL,
    slide_id varchar(48) NOT NULL,
    version integer NOT NULL,
    operator_id uuid NOT NULL REFERENCES identity_user(id),
    operator_name varchar(80) NOT NULL,
    status varchar(12) NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (city_code, slide_id, version),
    FOREIGN KEY (city_code, slide_id) REFERENCES content_home_slide(city_code, id)
);
