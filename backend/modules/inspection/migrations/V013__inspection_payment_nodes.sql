-- S4-1：巡检付款节点、确认方案后的取消与改期、退回整改（docs/redesign/08-后续开发计划.md §5 D9、C3、B2）。
-- 契约 openapi-inspection-service-flow.yaml 2.3.0。只加不改：存量服务单（payment_mode = NONE）和存量巡检次、交付的数据都满足新约束。

-- 1. 服务单状态加 AWAITING_PAYMENT（确认方案后、首付到账前）和 CANCELLED（第 1 次巡检开始前取消）。
ALTER TABLE inspection_service_order
    DROP CONSTRAINT inspection_service_order_status_check,
    ADD CONSTRAINT inspection_service_order_status_check
        CHECK (status IN (
            'AWAITING_PAYMENT',
            'ARRANGING',
            'IN_PROGRESS',
            'DELIVERED',
            'ACCEPTED',
            'CANCELLED'
        )),
    ADD COLUMN cancelled_at timestamptz,
    ADD COLUMN cancelled_by uuid,
    ADD COLUMN cancel_reason varchar(200),
    ADD CONSTRAINT ck_inspection_order_cancelled
        CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL));

-- 2. 巡检次状态加 REWORK：客户退回整改后，服务商重新交付之前。
--    original_planned_date：第一次改期生效时记下原定日期，页面写“原定 12/01，服务商已改期”。
ALTER TABLE inspection_service_visit
    DROP CONSTRAINT inspection_service_visit_status_check,
    ADD CONSTRAINT inspection_service_visit_status_check
        CHECK (status IN (
            'PENDING_SCHEDULE',
            'SCHEDULED',
            'IN_PROGRESS',
            'REWORK',
            'DELIVERED',
            'ACCEPTED'
        )),
    ADD COLUMN original_planned_date date;

-- 3. 交付状态加 RETURNED：被退回整改的交付；服务商重新交付时同一行改回 PUBLISHED、文件整组替换。
ALTER TABLE inspection_delivery
    DROP CONSTRAINT inspection_delivery_status_check,
    ADD CONSTRAINT inspection_delivery_status_check
        CHECK (status IN ('PUBLISHED', 'RETURNED', 'ACCEPTED')),
    ADD COLUMN revision integer NOT NULL DEFAULT 1 CHECK (revision > 0);

-- 4. 付款节点：首付退款记在节点上（退款进度以交易模块为准，这里只记退款单 ID）。
ALTER TABLE inspection_order_payment_node
    ADD COLUMN refund_id uuid,
    ADD COLUMN refund_requested_at timestamptz,
    ADD CONSTRAINT ck_inspection_payment_node_refund
        CHECK (refund_id IS NULL OR (paid_at IS NOT NULL AND node_type = 'DOWN_PAYMENT'));

-- 5. 付款尝试：节点每发起一次付款建一张交易订单（交易订单的 source_ref 就是这里的 id）。
--    超时或取消只关这一次，节点仍待付；同一节点同时只能有一笔待付或已付的尝试。
CREATE TABLE inspection_payment_attempt (
    id uuid PRIMARY KEY,
    service_order_id uuid NOT NULL REFERENCES inspection_service_order(id) ON DELETE RESTRICT,
    node_id uuid NOT NULL REFERENCES inspection_order_payment_node(id) ON DELETE RESTRICT,
    trade_order_id uuid NOT NULL UNIQUE,
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    status varchar(12) NOT NULL CHECK (status IN ('OPEN', 'PAID', 'CLOSED')),
    pay_before timestamptz NOT NULL,
    paid_at timestamptz,
    closed_at timestamptz,
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CHECK ((status = 'PAID') = (paid_at IS NOT NULL)),
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_inspection_payment_attempt_live
    ON inspection_payment_attempt (node_id)
    WHERE status IN ('OPEN', 'PAID');
CREATE INDEX idx_inspection_payment_attempt_order
    ON inspection_payment_attempt (service_order_id, created_at);

-- 6. 改期申请：客户在该次巡检开始前 24 小时申请，服务商确认或拒绝；同一次巡检同时只能有一个待处理的申请。
CREATE TABLE inspection_visit_reschedule (
    id uuid PRIMARY KEY,
    service_order_id uuid NOT NULL REFERENCES inspection_service_order(id) ON DELETE RESTRICT,
    visit_id uuid NOT NULL REFERENCES inspection_service_visit(id) ON DELETE RESTRICT,
    from_date date NOT NULL,
    requested_date date NOT NULL,
    reason varchar(200) NOT NULL,
    status varchar(12) NOT NULL CHECK (status IN ('REQUESTED', 'APPROVED', 'REJECTED')),
    decision_note varchar(200),
    requested_by uuid NOT NULL,
    requested_at timestamptz NOT NULL,
    decided_by uuid,
    decided_at timestamptz,
    CHECK ((status = 'REQUESTED') = (decided_at IS NULL)),
    CHECK (requested_date <> from_date)
);

CREATE UNIQUE INDEX uq_inspection_visit_reschedule_open
    ON inspection_visit_reschedule (visit_id)
    WHERE status = 'REQUESTED';
CREATE INDEX idx_inspection_visit_reschedule_order
    ON inspection_visit_reschedule (service_order_id, requested_at);

-- 7. 退回整改记录：一次交付可以被退回多次，每次一行；服务商重新交付时记下 resolved_at。
CREATE TABLE inspection_visit_rework (
    id uuid PRIMARY KEY,
    service_order_id uuid NOT NULL REFERENCES inspection_service_order(id) ON DELETE RESTRICT,
    visit_id uuid NOT NULL REFERENCES inspection_service_visit(id) ON DELETE RESTRICT,
    delivery_id uuid NOT NULL REFERENCES inspection_delivery(id) ON DELETE RESTRICT,
    delivery_revision integer NOT NULL CHECK (delivery_revision > 0),
    reason varchar(500) NOT NULL,
    requested_by uuid NOT NULL,
    requested_at timestamptz NOT NULL,
    resolved_at timestamptz,
    UNIQUE (delivery_id, delivery_revision)
);

CREATE INDEX idx_inspection_visit_rework_visit
    ON inspection_visit_rework (visit_id, requested_at);

-- 8. 状态迁移审计加付款尝试和改期申请。
ALTER TABLE inspection_state_transition
    DROP CONSTRAINT inspection_state_transition_aggregate_type_check,
    ADD CONSTRAINT inspection_state_transition_aggregate_type_check
        CHECK (aggregate_type IN (
            'RESERVATION',
            'PLAN',
            'SERVICE_ORDER',
            'SERVICE_VISIT',
            'DELIVERY',
            'PAYMENT_ATTEMPT',
            'RESCHEDULE'
        ));
