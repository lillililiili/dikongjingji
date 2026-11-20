-- R6-5：交易物流和活动提醒的站内信（docs/redesign/06-原型对齐修正计划.md §3 R6-5）。
-- 分类加 TRADE_LOGISTICS（交易物流：商品订单已发货、可以自提了、退款已原路退回）
-- 和 ACTIVITY_REMINDER（活动提醒：报名成功、报名已退款、到馆预约成功、到馆已改期）。
-- 跳转目标加 TRADE_ORDER（商品订单）、ACTIVITY_ENROLLMENT（报名凭证，ID 是报名单）、
-- ACTIVITY_ORDER（收费报名的报名订单，ID 是报名单）、VENUE_BOOKING（到馆预约）。
-- 已有的通知都是服务进度、需求与报价、客服消息，仍然满足新的约束。
ALTER TABLE notification_message DROP CONSTRAINT notification_message_category_check;
ALTER TABLE notification_message ADD CONSTRAINT notification_message_category_check
    CHECK (category IN ('SERVICE_PROGRESS', 'DEMAND_QUOTE', 'CUSTOMER_SERVICE', 'TRADE_LOGISTICS', 'ACTIVITY_REMINDER'));
ALTER TABLE notification_message DROP CONSTRAINT notification_message_target_type_check;
ALTER TABLE notification_message ADD CONSTRAINT notification_message_target_type_check
    CHECK (target_type IN ('INSPECTION_PLAN', 'INSPECTION_FULFILLMENT', 'INSPECTION_DELIVERY', 'DEMAND', 'CONVERSATION',
                           'TRADE_ORDER', 'ACTIVITY_ENROLLMENT', 'ACTIVITY_ORDER', 'VENUE_BOOKING'));
