-- F1：需求与报价的站内信（docs/redesign/03-App实现任务计划.md §22.1）。
-- 分类加 DEMAND_QUOTE（“消息”页的“需求与报价”一类），跳转目标加 DEMAND（需求 ID，页面自己再读报价）。
-- 已有的通知都是巡检服务进度，仍然满足新的约束。
ALTER TABLE notification_message DROP CONSTRAINT notification_message_category_check;
ALTER TABLE notification_message ADD CONSTRAINT notification_message_category_check
    CHECK (category IN ('SERVICE_PROGRESS', 'DEMAND_QUOTE'));
ALTER TABLE notification_message DROP CONSTRAINT notification_message_target_type_check;
ALTER TABLE notification_message ADD CONSTRAINT notification_message_target_type_check
    CHECK (target_type IN ('INSPECTION_PLAN', 'INSPECTION_FULFILLMENT', 'INSPECTION_DELIVERY', 'DEMAND'));
