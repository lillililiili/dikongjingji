-- E09：收费活动报名接入交易模块（docs/redesign/03-App实现任务计划.md §20）。
-- 收费报名提交时就占名额、建交易订单；付款前报名单和凭证都是“待付款”，超时或取消关闭后释放名额。
-- 已有的数据都是免费报名（CONFIRMED / CANCELLED，没有交易订单），下面每一条新约束对它们都成立。

-- ---- 报名单 ----

-- 状态加 PENDING_PAYMENT（待付款）和 CLOSED（没付款就关闭了）。
ALTER TABLE activity_enrollment DROP CONSTRAINT activity_enrollment_status_check;
ALTER TABLE activity_enrollment ADD CONSTRAINT activity_enrollment_status_check
    CHECK (status IN ('PENDING_PAYMENT', 'CONFIRMED', 'CANCELLED', 'CLOSED'));

-- 关闭时间；报名时的单价和总金额（分）；交易订单 ID（交易模块的表，不加外键，同一张交易订单只对应一张报名单）；
-- 付款截止时间（提交后 15 分钟，且不晚于报名截止）。
ALTER TABLE activity_enrollment ADD COLUMN closed_at timestamptz;
ALTER TABLE activity_enrollment ADD COLUMN unit_amount_minor bigint;
ALTER TABLE activity_enrollment ADD COLUMN amount_minor bigint;
ALTER TABLE activity_enrollment ADD COLUMN trade_order_id uuid;
ALTER TABLE activity_enrollment ADD COLUMN pay_before timestamptz;
ALTER TABLE activity_enrollment ADD CONSTRAINT uq_activity_enrollment_trade_order UNIQUE (trade_order_id);

ALTER TABLE activity_enrollment ADD CONSTRAINT ck_activity_enrollment_closed
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL));
-- 免费报名没有交易订单、没有金额，也不会待付款或关闭；收费报名的四项都有，金额是单价的整数倍（人数）。
-- 每一项都写明 IS NOT NULL：CHECK 遇到 NULL 的比较会算通过。
ALTER TABLE activity_enrollment ADD CONSTRAINT ck_activity_enrollment_payment
    CHECK (
        (trade_order_id IS NULL
            AND unit_amount_minor IS NULL
            AND amount_minor IS NULL
            AND pay_before IS NULL
            AND status IN ('CONFIRMED', 'CANCELLED'))
        OR (trade_order_id IS NOT NULL
            AND unit_amount_minor IS NOT NULL
            AND amount_minor IS NOT NULL
            AND pay_before IS NOT NULL
            AND unit_amount_minor > 0
            AND amount_minor > 0
            AND amount_minor % unit_amount_minor = 0)
    );

-- ---- 凭证 ----

-- 状态加 PENDING_PAYMENT：凭证号已经生成，但付款前不给二维码、不能核销。
ALTER TABLE activity_enrollment_ticket DROP CONSTRAINT activity_enrollment_ticket_status_check;
ALTER TABLE activity_enrollment_ticket ADD CONSTRAINT activity_enrollment_ticket_status_check
    CHECK (status IN ('PENDING_PAYMENT', 'ACTIVE', 'CHECKED_IN', 'CANCELLED'));

-- 取消原因：BUYER 报名人取消；PAYMENT_CLOSED 没付款、订单关闭。已有的取消都是报名人取消的，补上。
ALTER TABLE activity_enrollment_ticket ADD COLUMN cancel_cause varchar(16)
    CHECK (cancel_cause IN ('BUYER', 'PAYMENT_CLOSED'));
UPDATE activity_enrollment_ticket SET cancel_cause = 'BUYER' WHERE status = 'CANCELLED' AND cancel_cause IS NULL;
ALTER TABLE activity_enrollment_ticket ADD CONSTRAINT ck_activity_ticket_cancel_cause
    CHECK ((status = 'CANCELLED') = (cancel_cause IS NOT NULL));

-- 收费报名取消后的退款（交易模块的退款单 ID，不加外键）。同一次取消的几张凭证共用一笔退款。
ALTER TABLE activity_enrollment_ticket ADD COLUMN refund_id uuid;
ALTER TABLE activity_enrollment_ticket ADD CONSTRAINT ck_activity_ticket_refund
    CHECK (refund_id IS NULL OR (cancel_cause IS NOT NULL AND cancel_cause = 'BUYER'));

-- “同一个参与人在同一场次只能有一张有效凭证”的唯一索引（V002）条件是 status <> 'CANCELLED'，
-- 待付款的凭证也占着这个位置：没付款的人不能在同一场再报一次。这里不用改。

-- ---- 状态变化记录 ----

-- 加付款到账、关闭、关闭后到账恢复、按凭证退款四类。
ALTER TABLE activity_enrollment_event DROP CONSTRAINT activity_enrollment_event_event_type_check;
ALTER TABLE activity_enrollment_event ADD CONSTRAINT activity_enrollment_event_event_type_check
    CHECK (event_type IN ('ENROLLED', 'TICKET_CANCELLED', 'ENROLLMENT_CANCELLED', 'TICKET_CHECKED_IN',
                          'PAYMENT_CONFIRMED', 'PAYMENT_CLOSED', 'PAYMENT_RESTORED', 'REFUND_REQUESTED'));
-- 付款到账、关闭、恢复由渠道结果或超时触发，没有操作人；其余的仍必须记是谁。
ALTER TABLE activity_enrollment_event ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE activity_enrollment_event ADD CONSTRAINT ck_activity_event_actor
    CHECK (actor_id IS NOT NULL
           OR event_type IN ('PAYMENT_CONFIRMED', 'PAYMENT_CLOSED', 'PAYMENT_RESTORED'));
