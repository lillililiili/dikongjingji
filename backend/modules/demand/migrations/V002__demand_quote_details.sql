-- S4-3、S4-4（docs/redesign/03-App实现任务计划.md §28，契约 contracts/openapi-demand.yaml 1.3.0）：
-- 1. 报价的对比项（原型 PlanCompare）：服务团队、夜间作业、报告时效（小时）、影像存档（天，0 = 不含）、首次进场日期。都可以为空。
-- 2. 报价明细行：一份报价（最新一版）的明细，改价时整组替换；有明细时各行金额之和等于报价总价（服务校验）。
-- 3. 选定后生成的通用服务订单：只存服务订单模块给的 ID，不建外键（服务订单表属于别的模块）。

ALTER TABLE demand_quote
    ADD COLUMN team_text varchar(20),
    ADD COLUMN night_work_text varchar(20),
    ADD COLUMN report_hours integer CHECK (report_hours BETWEEN 1 AND 720),
    ADD COLUMN archive_days integer CHECK (archive_days BETWEEN 0 AND 3650),
    ADD COLUMN first_visit_date date;

CREATE TABLE demand_quote_line (
    quote_id uuid NOT NULL REFERENCES demand_quote(id) ON DELETE CASCADE,
    sort_order integer NOT NULL,
    name varchar(20) NOT NULL,
    quantity integer CHECK (quantity BETWEEN 1 AND 999),
    unit varchar(4),
    unit_amount_minor bigint CHECK (unit_amount_minor > 0),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    PRIMARY KEY (quote_id, sort_order),
    CONSTRAINT ck_demand_quote_line_unit CHECK (
        (quantity IS NULL AND unit IS NULL AND unit_amount_minor IS NULL)
        OR (quantity IS NOT NULL AND unit IS NOT NULL AND unit_amount_minor IS NOT NULL
            AND amount_minor = quantity * unit_amount_minor)
    )
);

ALTER TABLE demand ADD COLUMN service_order_id uuid UNIQUE;
ALTER TABLE demand ADD CONSTRAINT ck_demand_service_order CHECK (service_order_id IS NULL OR status = 'CHOSEN');
