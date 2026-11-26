ALTER TABLE inspection_portal_content
    DROP CONSTRAINT inspection_portal_content_content_type_check,
    ADD CONSTRAINT inspection_portal_content_content_type_check
        CHECK (content_type IN ('HERO','GUARANTEE','PROCESS','SCENE'));

CREATE TABLE inspection_portal_change (
    content_id uuid NOT NULL REFERENCES inspection_portal_content(id),
    version integer NOT NULL,
    operator_id uuid NOT NULL REFERENCES identity_user(id),
    operator_name varchar(80) NOT NULL,
    status varchar(16) NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (content_id, version)
);
