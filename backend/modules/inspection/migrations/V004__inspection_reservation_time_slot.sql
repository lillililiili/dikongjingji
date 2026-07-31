ALTER TABLE inspection_reservation
    ADD COLUMN preferred_time_slot_code varchar(16) NOT NULL DEFAULT 'MORNING'
        CHECK (preferred_time_slot_code IN (
            'MORNING',
            'AFTERNOON',
            'EVENING'
        ));

ALTER TABLE inspection_reservation
    ALTER COLUMN preferred_time_slot_code DROP DEFAULT;

CREATE INDEX idx_inspection_reservation_slot_capacity
    ON inspection_reservation (
        service_id,
        city_code,
        preferred_service_date,
        preferred_time_slot_code,
        status
    );
