-- S5-3：活动候补、改期和结果记录的站内信（docs/redesign/08-后续开发计划.md §5“候补、团队报名、改期”，
-- 契约 contracts/openapi-notification.yaml 1.6.0）。分类仍是 ACTIVITY_REMINDER（活动提醒），事件码三个：
-- ACTIVITY_WAITLIST_OFFERED（候补转正，跳到候补详情）、ACTIVITY_RESCHEDULED（改期成功，跳到报名凭证）、
-- ACTIVITY_RESULT_RECORDED（主办方录了结果，跳到报名凭证）。event_code 不受约束，只给跳转目标加 ACTIVITY_WAITLIST
-- （ID 是候补）。
-- 下面的取值包括 S5-9（V006，另一个分支）加的 SYSTEM_NOTICE：两边谁先合并都满足；V006 不在时也没有影响。
ALTER TABLE notification_message DROP CONSTRAINT notification_message_category_check;
ALTER TABLE notification_message ADD CONSTRAINT notification_message_category_check
    CHECK (category IN ('SERVICE_PROGRESS', 'DEMAND_QUOTE', 'CUSTOMER_SERVICE', 'TRADE_LOGISTICS', 'ACTIVITY_REMINDER',
                        'LIVE_REMINDER', 'SYSTEM_NOTICE'));
ALTER TABLE notification_message DROP CONSTRAINT notification_message_target_type_check;
ALTER TABLE notification_message ADD CONSTRAINT notification_message_target_type_check
    CHECK (target_type IN ('INSPECTION_PLAN', 'INSPECTION_FULFILLMENT', 'INSPECTION_DELIVERY', 'DEMAND', 'CONVERSATION',
                           'TRADE_ORDER', 'ACTIVITY_ENROLLMENT', 'ACTIVITY_ORDER', 'VENUE_BOOKING', 'LIVE_ROOM',
                           'SYSTEM_NOTICE', 'ACTIVITY_WAITLIST'));
