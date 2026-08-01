ALTER TABLE inspection_service
    ADD COLUMN completed_count bigint NOT NULL DEFAULT 0
        CHECK (completed_count >= 0);
