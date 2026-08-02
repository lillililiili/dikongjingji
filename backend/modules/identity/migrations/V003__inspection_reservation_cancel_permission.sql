INSERT INTO identity_permission (permission_code, permission_name)
VALUES ('inspection.reservation.cancel.own', '取消本人巡检预约')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO identity_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM identity_role r
JOIN identity_permission p
  ON p.permission_code = 'inspection.reservation.cancel.own'
WHERE r.role_code = 'CUSTOMER'
ON CONFLICT (role_id, permission_id) DO NOTHING;
