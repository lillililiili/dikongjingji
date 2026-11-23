-- S4-4 后端补字段（docs/redesign/08-后续开发计划.md §5）：履约页“持证飞手 N 名”（原型画板 Fulfillment、FulfillOne）。
-- 服务商在工作台安排服务团队时逐人标记是否持证飞手（契约 contracts/openapi-inspection-service-flow.yaml 2.5.0）。
-- 只有服务团队成员能标记，设备一律为 false；旧安排没有标记，全部按 false。
-- V013 留给巡检付款节点那一批。

ALTER TABLE inspection_order_assignment
    ADD COLUMN certified_pilot boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT ck_inspection_order_assignment_certified_pilot
        CHECK (assignment_type = 'TEAM_MEMBER' OR NOT certified_pilot);
