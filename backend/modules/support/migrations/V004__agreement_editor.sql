ALTER TABLE platform_agreement
    ADD COLUMN draft_effective_on date,
    ADD COLUMN revision integer NOT NULL DEFAULT 0 CHECK (revision >= 0);

CREATE TABLE platform_agreement_change (
    agreement_id uuid NOT NULL REFERENCES platform_agreement(id),
    revision integer NOT NULL,
    action varchar(12) NOT NULL CHECK (action IN ('CREATE','UPDATE','PUBLISH')),
    operator_id uuid NOT NULL REFERENCES identity_user(id),
    operator_name varchar(80) NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (agreement_id, revision)
);
