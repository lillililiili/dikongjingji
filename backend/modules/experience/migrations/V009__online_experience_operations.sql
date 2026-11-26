ALTER TABLE experience_item ADD COLUMN video_url varchar(500)
    CHECK (video_url IS NULL OR type = 'RECORDED_DEMO');

CREATE TABLE experience_item_change (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    experience_id uuid NOT NULL REFERENCES experience_item(id) ON DELETE RESTRICT,
    version integer NOT NULL,
    action varchar(16) NOT NULL CHECK (action IN ('CREATE','UPDATE','PUBLISH','OFFLINE')),
    operator_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    operator_name varchar(80) NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (experience_id, version)
);

-- Existing records can be reconstructed only from the project as it exists at migration time.
ALTER TABLE experience_record ADD COLUMN item_snapshot jsonb;
CREATE FUNCTION experience_item_snapshot(i experience_item) RETURNS jsonb
LANGUAGE sql STABLE AS $$
    SELECT jsonb_build_object('type', i.type, 'title', i.title, 'coverUrl', i.cover_url,
                              'productId', i.product_id, 'simulationTemplate', i.simulation_template)
$$;
UPDATE experience_record r SET item_snapshot = experience_item_snapshot(i)
FROM experience_item i WHERE i.id = r.experience_id;
ALTER TABLE experience_record ALTER COLUMN item_snapshot SET NOT NULL;

CREATE FUNCTION capture_experience_item() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    SELECT experience_item_snapshot(i) INTO STRICT NEW.item_snapshot
        FROM experience_item i WHERE i.id = NEW.experience_id;
    RETURN NEW;
END $$;
CREATE TRIGGER capture_experience_item BEFORE INSERT ON experience_record
FOR EACH ROW EXECUTE FUNCTION capture_experience_item();
