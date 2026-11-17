-- Mall catalog: product → sku → offer (merchant × sku × city × price), plus specs and pack lists.
-- Read-only in this version; cart and order tables come with the trade step.
-- identity_organization and pgcrypto are created by the identity migrations, which run first.
CREATE TABLE mall_product (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    category varchar(16) NOT NULL
        CHECK (category IN ('DRONE', 'ROBOT', 'PARTS', 'RENTAL', 'COURSE')),
    name varchar(160) NOT NULL,
    summary varchar(300),
    cover_url varchar(500),
    sale_mode varchar(16) NOT NULL CHECK (sale_mode IN ('BUY', 'RENT', 'INQUIRY')),
    experience_badge varchar(24)
        CHECK (experience_badge IN ('ONLINE_EXPERIENCE', 'VENUE_DISPLAY')),
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'OFFLINE', 'DELETED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_mall_product_published ON mall_product (status, category, sort_order, id);

CREATE TABLE mall_product_spec (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES mall_product(id) ON DELETE RESTRICT,
    label varchar(40) NOT NULL,
    value varchar(200) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_mall_product_spec_product ON mall_product_spec (product_id, status, sort_order, id);

CREATE TABLE mall_product_pack_item (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES mall_product(id) ON DELETE RESTRICT,
    name varchar(80) NOT NULL,
    quantity integer NOT NULL CHECK (quantity > 0),
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_mall_product_pack_item_product
    ON mall_product_pack_item (product_id, status, sort_order, id);

CREATE TABLE mall_sku (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES mall_product(id) ON DELETE RESTRICT,
    name varchar(80) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_mall_sku_product ON mall_sku (product_id, status, sort_order, id);

-- city_code NULL means the offer sells nationwide.
CREATE TABLE mall_offer (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sku_id uuid NOT NULL REFERENCES mall_sku(id) ON DELETE RESTRICT,
    merchant_id uuid NOT NULL REFERENCES identity_organization(id) ON DELETE RESTRICT,
    merchant_kind varchar(24) NOT NULL
        CHECK (merchant_kind IN ('FACTORY_DIRECT', 'AUTHORIZED_DEALER', 'LOCAL_STORE')),
    city_code varchar(16),
    price_amount_minor bigint CHECK (price_amount_minor >= 0),
    currency char(3) NOT NULL DEFAULT 'CNY' CHECK (currency = 'CNY'),
    price_unit varchar(16) NOT NULL DEFAULT 'ITEM'
        CHECK (price_unit IN ('ITEM', 'DAY', 'HALF_DAY')),
    delivery_note varchar(200),
    shipping_note varchar(200),
    fulfilled_from varchar(120),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_mall_offer_sku_city ON mall_offer (sku_id, status, city_code);
CREATE INDEX idx_mall_offer_merchant ON mall_offer (merchant_id, status);
