ALTER TABLE platform_map_place
    ADD COLUMN management_id uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    ADD COLUMN version integer NOT NULL DEFAULT 0;

CREATE TABLE platform_map_place_change (
    management_id uuid NOT NULL REFERENCES platform_map_place(management_id),
    version integer NOT NULL,
    operator_id uuid NOT NULL REFERENCES identity_user(id),
    operator_name varchar(80) NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now(),
    previous_value jsonb,
    current_value jsonb NOT NULL,
    PRIMARY KEY (management_id, version)
);
