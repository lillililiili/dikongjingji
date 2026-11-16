-- T03：服务详情的计价与取消说明（契约 openapi-inspection-catalog.yaml 1.1.0）。
--
-- 1. inspection_service.cancellation_note
--    来源：后台录入的取消说明展示文案（契约 InspectionServiceDetail.cancellationNote，上限 500）。
--    为空含义：该服务没有配置取消说明，接口返回 null，客户端隐藏该行；取消是否允许
--    仍由服务流程契约的状态机决定，本列只做展示。
--    存量不回填：现有服务全部保持 NULL，不编造文案。
--
-- 2. inspection_service_detail_item.section_type 增加 PRICE_FACTOR
--    来源：影响价格的因素（契约 InspectionServiceDetail.priceFactors，最多 10 条），
--    复用明细项表，title 为展示文案，display_order 为后台排序。
--    存量不回填：没有 PRICE_FACTOR 行的服务接口返回 null。
--    V004 在 CREATE TABLE 里用列级 CHECK 定义取值集合，PostgreSQL 自动命名为
--    inspection_service_detail_item_section_type_check，这里按该名称替换约束。

ALTER TABLE inspection_service
    ADD COLUMN cancellation_note varchar(500);

ALTER TABLE inspection_service_detail_item
    DROP CONSTRAINT inspection_service_detail_item_section_type_check;

ALTER TABLE inspection_service_detail_item
    ADD CONSTRAINT inspection_service_detail_item_section_type_check
        CHECK (section_type IN (
            'CAPABILITY',
            'PROCESS',
            'DELIVERABLE',
            'GUARANTEE',
            'PRICE_FACTOR'
        ));
