-- 只在客户实际确认转预约时记录来源；已有需求及普通预约无需回填。
CREATE TABLE demand_inspection_booking (
    demand_id uuid PRIMARY KEY REFERENCES demand(id),
    reservation_id uuid NOT NULL UNIQUE REFERENCES inspection_reservation(id),
    quote_id uuid NOT NULL UNIQUE REFERENCES demand_quote(id),
    quote_version integer NOT NULL CHECK (quote_version >= 1),
    provider_id uuid NOT NULL REFERENCES identity_organization(id),
    provider_name text NOT NULL,
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    summary text NOT NULL,
    account_id uuid NOT NULL REFERENCES identity_user(id),
    idempotency_key uuid NOT NULL,
    request_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, idempotency_key)
);
