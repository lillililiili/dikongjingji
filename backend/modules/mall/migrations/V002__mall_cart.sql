-- 购物车（2026-11-17 用户确认按原型做）：只放有标价的“购买”商品，租赁和询价商品不进购物车；
-- 一次只结算一个商家。到“提交订单”为止，这一步不建订单表。

-- 在售商家的配送方式：快递和到店自提，至少有一种。自提点写在 pickup_point，有自提点才能自提。
-- shipping_fee_minor 是这个供给走快递时一单的运费（分），一单里取各商品的最大值。
ALTER TABLE mall_offer
    ADD COLUMN express_available boolean NOT NULL DEFAULT true,
    ADD COLUMN pickup_point varchar(120),
    ADD COLUMN shipping_fee_minor bigint NOT NULL DEFAULT 0 CHECK (shipping_fee_minor >= 0);

ALTER TABLE mall_offer
    ADD CONSTRAINT ck_mall_offer_fulfilment
    CHECK (express_available OR pickup_point IS NOT NULL);

-- 登录账号自己的购物车。一行是“某个商家的某个规格”，同一个供给再加一次只加数量。
-- 不存价格：每次读取都按当前价格和当前城市重新判断能不能买。
CREATE TABLE mall_cart_item (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    offer_id uuid NOT NULL REFERENCES mall_offer(id) ON DELETE RESTRICT,
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 99),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, offer_id)
);

CREATE INDEX idx_mall_cart_item_account ON mall_cart_item (account_id, updated_at DESC, id);
