-- 运营端首页“经营概览”的演示经营数据：只给本机开发库用，给人演示看板长什么样。不是 Flyway 迁移，不会自动进任何环境。
--   灌入：docker exec -i <postgres 容器> psql -U <用户> -d <库> -v ON_ERROR_STOP=1 < infra/dev/dashboard-demo/seed.sql
--   清掉：同样的方式执行 purge.sql
-- 全部数据都带演示标记，和已有的示例商家、开发账号、端到端测试数据互不相干：
--   客户登录名 demo-dash-0001…、显示名“演示客户…”，没有手机号，口令不可用（登录不了）；
--   机构编码 DEMO-DASH-01…、名称以“演示·”开头，收款子商户号是 19900000xx 这样的占位号；
--   订单号 DEMO 开头，没有微信支付记录，非商城订单的业务单 ID 是随机值（对不上任何报名、预约）。
-- 时间铺在最近 200 天，越近越多，所以 7、30、90 天的环比都有数。每次执行先清掉上一份再重建，随机数固定种子。
\set ON_ERROR_STOP on

BEGIN;

-- 先清掉上一份（和 purge.sql 一样）。
DELETE FROM trade_order_line
WHERE order_id IN (SELECT id FROM trade_order WHERE order_no LIKE 'DEMO%');
DELETE FROM trade_order WHERE order_no LIKE 'DEMO%';

DELETE FROM identity_login_session
WHERE user_id IN (SELECT id FROM identity_user WHERE login_name LIKE 'demo-dash-%');
DELETE FROM identity_user_role
WHERE user_id IN (SELECT id FROM identity_user WHERE login_name LIKE 'demo-dash-%');
DELETE FROM identity_user WHERE login_name LIKE 'demo-dash-%';

DELETE FROM trade_merchant_account
WHERE organization_id IN (SELECT id FROM identity_organization WHERE organization_code LIKE 'DEMO-DASH-%');
DELETE FROM identity_organization WHERE organization_code LIKE 'DEMO-DASH-%';

SELECT setseed(0.2026);

-- 演示机构：36 家经营机构，前 26 家登记了收款账户；30 家资质已审核。
CREATE TEMP TABLE demo_org ON COMMIT DROP AS
SELECT n,
       gen_random_uuid() AS id,
       'DEMO-DASH-' || lpad(n::text, 2, '0') AS code,
       name,
       category,
       (ARRAY['370500','370100','370200','370700','370300','371300'])[1 + (n % 6)] AS city_code,
       now() - make_interval(days => 190 - n * 5) AS created_at
FROM (VALUES
    (1, '演示·黄河口无人机旗舰店', 'MALL'), (2, '演示·齐鲁机器人专营店', 'MALL'),
    (3, '演示·泉城科创器材', 'MALL'), (4, '演示·青岛海天航模', 'MALL'),
    (5, '演示·潍坊风筝与飞行器', 'MALL'), (6, '演示·鲁中教育装备', 'MALL'),
    (7, '演示·东营青少年科技营', 'ACTIVITY'), (8, '演示·济南研学联盟', 'ACTIVITY'),
    (9, '演示·青岛海洋研学', 'ACTIVITY'), (10, '演示·黄河口低空体验馆', 'VENUE'),
    (11, '演示·泉城机器人体验中心', 'VENUE'), (12, '演示·岛城飞行体验馆', 'VENUE'),
    (13, '演示·飞手培训学院', 'COURSE'), (14, '演示·少儿编程与机器人课堂', 'COURSE'),
    (15, '演示·鲁北设备租赁', 'RENTAL'), (16, '演示·齐鲁航拍租赁', 'RENTAL'),
    (17, '演示·黄河口测绘', 'PRO_SERVICE'), (18, '演示·云端航拍工作室', 'PRO_SERVICE'),
    (19, '演示·胜利电力巡检', 'INSPECTION'), (20, '演示·鲁电智能巡检', 'INSPECTION'),
    (21, '演示·油田管线巡检', 'INSPECTION'), (22, '演示·临沂农业植保', 'PRO_SERVICE'),
    (23, '演示·淄博工业机器人', 'MALL'), (24, '演示·威海海岸飞行营地', 'ACTIVITY'),
    (25, '演示·济宁机器人课堂', 'COURSE'), (26, '演示·滨州设备维护', 'PRO_SERVICE'),
    (27, '演示·东营创客空间', 'NONE'), (28, '演示·青岛智能硬件', 'NONE'),
    (29, '演示·潍坊航空科普', 'NONE'), (30, '演示·济南低空物流', 'NONE'),
    (31, '演示·临沂智慧农业', 'NONE'), (32, '演示·淄博无人机协会服务', 'NONE'),
    (33, '演示·日照海上巡检', 'NONE'), (34, '演示·泰安景区航拍', 'NONE'),
    (35, '演示·聊城科教装备', 'NONE'), (36, '演示·德州机器人维修', 'NONE')
) AS t(n, name, category);

INSERT INTO identity_organization (id, organization_code, organization_name, organization_type, status,
                                   created_at, updated_at, qualification_reviewed_at, city_code)
SELECT id, code, name, 'PROVIDER', 'ACTIVE', created_at, created_at,
       CASE WHEN n <= 30 THEN created_at + interval '2 days' END, city_code
FROM demo_org;

INSERT INTO trade_merchant_account (organization_id, channel, sub_mchid, active, created_at, updated_at)
SELECT id, 'WECHAT', '19900000' || lpad(n::text, 2, '0'), true, created_at + interval '3 days',
       created_at + interval '3 days'
FROM demo_org
WHERE category <> 'NONE';

-- 演示客户：2400 个，注册时间越近越多。
CREATE TEMP TABLE demo_customer ON COMMIT DROP AS
SELECT n,
       gen_random_uuid() AS id,
       now() - make_interval(secs => (200 * 86400 * (1 - sqrt(random())))::int) AS created_at
FROM generate_series(1, 2400) AS n;

INSERT INTO identity_user (id, login_name, display_name, password_hash, status, registered_via, created_at,
                           updated_at)
SELECT id, 'demo-dash-' || lpad(n::text, 4, '0'), '演示客户' || lpad(n::text, 4, '0'), '!demo-no-login',
       'ACTIVE', 'PHONE', created_at, created_at
FROM demo_customer;

INSERT INTO identity_user_role (user_id, role_id, valid_from, created_at)
SELECT c.id, r.id, c.created_at, c.created_at
FROM demo_customer c
CROSS JOIN identity_role r
WHERE r.role_code = 'CUSTOMER';

-- 最近使用时间：一半多的客户在最近 30 天用过。
INSERT INTO identity_login_session (user_id, device_name, platform, city_code, login_method, signed_in_at,
                                    last_active_at)
SELECT id, '演示设备', CASE WHEN random() < 0.62 THEN 'ANDROID' ELSE 'IOS' END, '370500', 'SMS_CODE',
       created_at,
       GREATEST(created_at, now() - make_interval(secs => (
           CASE WHEN random() < 0.6 THEN random() * 30 ELSE 30 + random() * 150 END * 86400)::int))
FROM demo_customer;

-- 订单：最近 200 天，每天的单量越近越多。
CREATE TEMP TABLE demo_slot ON COMMIT DROP AS
SELECT d AS days_ago, s
FROM generate_series(0, 199) AS d
CROSS JOIN LATERAL generate_series(1, greatest(1, floor((5 + 36 * power((200 - d) / 200.0, 1.7))
                                                         * (0.7 + 0.6 * random()))::int)) AS s;

CREATE TEMP TABLE demo_pick ON COMMIT DROP AS
SELECT row_number() OVER () AS n,
       now() - make_interval(days => days_ago, secs => (random() * 50000)::int) - interval '20 minutes'
           AS created_at,
       random() AS source_roll,
       random() AS city_roll,
       random() AS amount_roll,
       random() AS status_roll,
       random() AS refund_roll,
       random() AS merchant_roll,
       random() AS buyer_roll
FROM demo_slot;

CREATE TEMP TABLE demo_order ON COMMIT DROP AS
SELECT p.n,
       gen_random_uuid() AS id,
       p.created_at,
       CASE WHEN p.source_roll < 0.58 THEN 'MALL'
            WHEN p.source_roll < 0.73 THEN 'ACTIVITY'
            WHEN p.source_roll < 0.84 THEN 'VENUE'
            WHEN p.source_roll < 0.91 THEN 'COURSE'
            WHEN p.source_roll < 0.95 THEN 'RENTAL'
            WHEN p.source_roll < 0.975 THEN 'PRO_SERVICE'
            ELSE 'INSPECTION' END AS source,
       CASE WHEN p.city_roll < 0.38 THEN '370500'
            WHEN p.city_roll < 0.58 THEN '370100'
            WHEN p.city_roll < 0.73 THEN '370200'
            WHEN p.city_roll < 0.82 THEN '370700'
            WHEN p.city_roll < 0.88 THEN '370300'
            WHEN p.city_roll < 0.93 THEN '371300'
            WHEN p.city_roll < 0.96 THEN '370800'
            WHEN p.city_roll < 0.98 THEN '371000'
            ELSE '371600' END AS city_code,
       p.amount_roll, p.status_roll, p.refund_roll, p.merchant_roll, p.buyer_roll
FROM demo_pick p;

CREATE TEMP TABLE demo_full ON COMMIT DROP AS
SELECT o.*,
       m.id AS merchant_id, m.name AS merchant_name, '19900000' || lpad(m.n::text, 2, '0') AS sub_mchid,
       b.id AS buyer_id,
       CASE o.source
           WHEN 'MALL' THEN (ARRAY[39900, 129900, 299900, 459900, 699900, 1299900])[1 + floor(o.amount_roll * 6)::int]
           WHEN 'ACTIVITY' THEN (ARRAY[9900, 19900, 29900, 39900])[1 + floor(o.amount_roll * 4)::int]
           WHEN 'VENUE' THEN (ARRAY[5900, 9800, 19800])[1 + floor(o.amount_roll * 3)::int]
           WHEN 'COURSE' THEN (ARRAY[19900, 59900, 99900])[1 + floor(o.amount_roll * 3)::int]
           WHEN 'RENTAL' THEN (ARRAY[80000, 160000, 300000])[1 + floor(o.amount_roll * 3)::int]
           WHEN 'PRO_SERVICE' THEN (ARRAY[300000, 800000, 1800000])[1 + floor(o.amount_roll * 3)::int]
           ELSE (ARRAY[500000, 1200000, 2800000])[1 + floor(o.amount_roll * 3)::int] END AS goods_minor,
       CASE o.source
           WHEN 'MALL' THEN (ARRAY['无人机套装', '教育机器人', '航拍无人机', '编程机器人套件', '行业无人机', '巡检机器人'])[1 + floor(o.amount_roll * 6)::int]
           WHEN 'ACTIVITY' THEN '青少年无人机研学营'
           WHEN 'VENUE' THEN '低空飞行体验'
           WHEN 'COURSE' THEN '无人机飞行入门课'
           WHEN 'RENTAL' THEN '航拍无人机租赁'
           WHEN 'PRO_SERVICE' THEN '航拍测绘服务'
           ELSE '输电线路巡检' END AS product_name
FROM demo_order o
CROSS JOIN LATERAL (
    SELECT g.id, g.name, g.n FROM demo_org g
    WHERE g.category = o.source
    ORDER BY g.n
    OFFSET floor(o.merchant_roll * (SELECT count(*) FROM demo_org h WHERE h.category = o.source))::int
    LIMIT 1
) m
CROSS JOIN LATERAL (
    SELECT c.id FROM demo_customer c
    WHERE c.created_at < o.created_at
    ORDER BY c.n
    OFFSET floor(o.buyer_roll * (SELECT count(*) FROM demo_customer d WHERE d.created_at < o.created_at))::int
    LIMIT 1
) b;

-- 状态：约 12% 没付款超时关闭；付了款的里约 5% 全额退款、3% 部分退款；商城 7 天前的已完成，近几天的已发货。
INSERT INTO trade_order (id, order_no, buyer_id, merchant_id, merchant_kind, merchant_name, sub_mchid, source,
                         city_code, status, delivery_mode, goods_minor, shipping_minor, payable_minor, paid_minor,
                         refunded_minor, buyer_note, pay_before, paid_at, shipped_at, completed_at, closed_at,
                         idempotency_key, request_hash, created_at, updated_at, source_ref)
SELECT id,
       'DEMO' || to_char(created_at AT TIME ZONE 'Asia/Shanghai', 'YYYYMMDD') || lpad(n::text, 8, '0'),
       buyer_id, merchant_id,
       CASE WHEN source = 'MALL' THEN (ARRAY['FACTORY_DIRECT', 'AUTHORIZED_DEALER', 'LOCAL_STORE'])[1 + (n % 3)] END,
       merchant_name, sub_mchid, source, city_code,
       CASE WHEN unpaid THEN 'CLOSED'
            WHEN full_refund THEN 'REFUNDED'
            WHEN source <> 'MALL' THEN 'PAID'
            WHEN created_at < now() - interval '7 days' THEN 'COMPLETED'
            ELSE 'SHIPPED' END,
       CASE WHEN source = 'MALL' THEN 'EXPRESS' ELSE 'NONE' END,
       goods_minor, 0, goods_minor,
       CASE WHEN unpaid THEN 0 ELSE goods_minor END,
       CASE WHEN unpaid THEN 0
            WHEN full_refund THEN goods_minor
            WHEN partial_refund THEN (goods_minor * 3 / 10)
            ELSE 0 END,
       NULL,
       created_at + interval '15 minutes',
       CASE WHEN NOT unpaid THEN created_at + interval '3 minutes' END,
       CASE WHEN NOT unpaid AND NOT full_refund AND source = 'MALL' THEN created_at + interval '1 day' END,
       CASE WHEN NOT unpaid AND NOT full_refund AND source = 'MALL' AND created_at < now() - interval '7 days'
            THEN created_at + interval '6 days' END,
       CASE WHEN unpaid THEN created_at + interval '15 minutes' END,
       gen_random_uuid(), md5(id::text) || md5(n::text),
       created_at, created_at,
       CASE WHEN source <> 'MALL' THEN gen_random_uuid() END
FROM (
    SELECT f.*,
           f.status_roll < 0.12 AS unpaid,
           f.status_roll >= 0.12 AND f.refund_roll < 0.05 AS full_refund,
           f.status_roll >= 0.12 AND f.refund_roll >= 0.05 AND f.refund_roll < 0.08 AS partial_refund
    FROM demo_full f
) x;

INSERT INTO trade_order_line (order_id, product_id, offer_id, product_name, sku_name, unit_amount_minor, quantity,
                              subtotal_minor, created_at)
SELECT id, gen_random_uuid(), gen_random_uuid(), '演示·' || product_name, '标准', goods_minor, 1, goods_minor,
       created_at
FROM demo_full;

COMMIT;

SELECT (SELECT count(*) FROM identity_user WHERE login_name LIKE 'demo-dash-%') AS demo_customers,
       (SELECT count(*) FROM identity_organization WHERE organization_code LIKE 'DEMO-DASH-%') AS demo_organizations,
       (SELECT count(*) FROM trade_order WHERE order_no LIKE 'DEMO%') AS demo_orders,
       (SELECT sum(paid_minor - refunded_minor) / 100 FROM trade_order WHERE order_no LIKE 'DEMO%') AS demo_net_yuan;
