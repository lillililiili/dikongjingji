ALTER TABLE identity_user
    ADD COLUMN default_organization_id uuid;

ALTER TABLE identity_user
    ADD CONSTRAINT fk_identity_user_default_organization
        FOREIGN KEY (default_organization_id)
        REFERENCES identity_organization(id)
        ON DELETE RESTRICT;

CREATE INDEX idx_identity_user_default_organization
    ON identity_user (default_organization_id);
