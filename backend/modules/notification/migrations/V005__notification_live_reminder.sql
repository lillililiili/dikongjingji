-- M20 直播提醒的站内信（docs/redesign/07-视频与直播实现计划.md §3，契约 contracts/openapi-notification.yaml 1.4.0）。
-- 分类加 LIVE_REMINDER（直播提醒：设了提醒的直播开始了），跳转目标加 LIVE_ROOM（直播间 ID，页面自己再读直播间）。
-- 开播的事务提交后写，同一直播间每个账号只记一条（sourceKey）。
-- 已有的通知都是服务进度、需求与报价、客服消息、交易物流、活动提醒，仍然满足新的约束。
ALTER TABLE notification_message DROP CONSTRAINT notification_message_category_check;
ALTER TABLE notification_message ADD CONSTRAINT notification_message_category_check
    CHECK (category IN ('SERVICE_PROGRESS', 'DEMAND_QUOTE', 'CUSTOMER_SERVICE', 'TRADE_LOGISTICS', 'ACTIVITY_REMINDER',
                        'LIVE_REMINDER'));
ALTER TABLE notification_message DROP CONSTRAINT notification_message_target_type_check;
ALTER TABLE notification_message ADD CONSTRAINT notification_message_target_type_check
    CHECK (target_type IN ('INSPECTION_PLAN', 'INSPECTION_FULFILLMENT', 'INSPECTION_DELIVERY', 'DEMAND', 'CONVERSATION',
                           'TRADE_ORDER', 'ACTIVITY_ENROLLMENT', 'ACTIVITY_ORDER', 'VENUE_BOOKING', 'LIVE_ROOM'));
