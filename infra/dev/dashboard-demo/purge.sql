-- 清掉 seed.sql 建的演示经营数据（只认演示标记：登录名 demo-dash-、机构编码 DEMO-DASH-、订单号 DEMO 开头）。
-- 只给本机开发库用：docker exec -i <postgres 容器> psql -U <用户> -d <库> < infra/dev/dashboard-demo/purge.sql
BEGIN;

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

COMMIT;
