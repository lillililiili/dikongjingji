-- 交易模块负责订单及付款，商城负责供给及库存。仅给新建的标价购买订单记账，不推算过去的库存。
-- 整单标记也覆盖下单时完全不限库存的订单，防止调成有限库存后重放时重新扣减。
CREATE TABLE mall_stock_order (
    order_id uuid PRIMARY KEY,
    line_signature text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE mall_stock_hold (
    order_id uuid NOT NULL REFERENCES mall_stock_order(order_id),
    offer_id uuid NOT NULL REFERENCES mall_offer(id),
    quantity integer NOT NULL CHECK (quantity > 0),
    status varchar(12) NOT NULL CHECK (status IN ('HELD','SOLD','RELEASED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (order_id, offer_id)
);
COMMENT ON COLUMN mall_offer.stock_quantity IS '当前可售数，NULL 为不限库存，已预占的件数不包含在此数中';
