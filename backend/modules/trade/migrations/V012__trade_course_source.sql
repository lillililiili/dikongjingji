-- S5-5：线上录播课接入交易（docs/redesign/09-课程实现说明.md；契约 contracts/openapi-trade.yaml 0.6.0）。
-- 订单来源加 COURSE，收款方是开课机构。V005 已经让非商城订单必须带业务单、不发货、没有商家类型，这里只放开来源的取值。
-- 几个业务模块并行接入交易、各自加来源，所以不重写整张取值表：读出约束里现有的取值，加上 COURSE 再建回去，
-- 和别的分支加来源的迁移谁先谁后都不会把对方的取值丢掉。已有的数据不用改。
DO $$
DECLARE
    existing text[];
BEGIN
    SELECT array_agg(DISTINCT value ORDER BY value)
    INTO existing
    FROM pg_constraint constraint_row,
         regexp_matches(pg_get_constraintdef(constraint_row.oid), '''([A-Z_]+)''', 'g') AS matched(value_list),
         unnest(matched.value_list) AS value
    WHERE constraint_row.conname = 'trade_order_source_check'
      AND constraint_row.conrelid = 'trade_order'::regclass;

    IF existing IS NULL THEN
        RAISE EXCEPTION 'trade_order_source_check not found';
    END IF;
    IF NOT ('COURSE' = ANY (existing)) THEN
        existing := array_append(existing, 'COURSE');
    END IF;

    ALTER TABLE trade_order DROP CONSTRAINT trade_order_source_check;
    EXECUTE format(
        'ALTER TABLE trade_order ADD CONSTRAINT trade_order_source_check CHECK (source IN (%s))',
        (SELECT string_agg(quote_literal(value), ', ' ORDER BY value) FROM unnest(existing) AS value));
END
$$;
