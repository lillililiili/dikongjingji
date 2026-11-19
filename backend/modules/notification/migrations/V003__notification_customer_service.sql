-- G1：客服消息的站内信（docs/redesign/03-App实现任务计划.md §26.1 第 5 条）。
-- 分类加 CUSTOMER_SERVICE（“客服消息”：商家、服务商或平台客服回复了你），跳转目标加 CONVERSATION（会话 ID）。
-- 同一个会话最多一条未读的客服消息：对方连着回复几条，只刷新那条未读的时间，不再加新的；读过之后的新回复再记一条。
-- 已有的通知都是服务进度、需求与报价，仍然满足新的约束。
ALTER TABLE notification_message DROP CONSTRAINT notification_message_category_check;
ALTER TABLE notification_message ADD CONSTRAINT notification_message_category_check
    CHECK (category IN ('SERVICE_PROGRESS', 'DEMAND_QUOTE', 'CUSTOMER_SERVICE'));
ALTER TABLE notification_message DROP CONSTRAINT notification_message_target_type_check;
ALTER TABLE notification_message ADD CONSTRAINT notification_message_target_type_check
    CHECK (target_type IN ('INSPECTION_PLAN', 'INSPECTION_FULFILLMENT', 'INSPECTION_DELIVERY', 'DEMAND', 'CONVERSATION'));

CREATE UNIQUE INDEX uq_notification_message_unread_conversation
    ON notification_message (recipient_id, target_type, target_id)
    WHERE category = 'CUSTOMER_SERVICE' AND status = 'ACTIVE' AND read_at IS NULL;
