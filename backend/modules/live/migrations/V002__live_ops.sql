-- S3-1 第三部分运营端“直播间”（docs/redesign/08-后续开发计划.md §2、§5 D11，契约 contracts/openapi-ops-live.yaml 1.0.0）：
-- 平台运营看全部直播间、强制结束、维护回看链接。每次改动在这里留一条，只追加；operator_name 是当时运营账号的显示名快照。
-- 开通直播主办（授 LIVE_HOST）在身份模块的“机构与资质”里给账号改角色，记在身份模块的机构操作记录里，不在这张表。
--   FORCE_END     强制结束（summary 是运营写的原因）
--   SET_REPLAY    设回看链接（summary 带平台名称和地址）
--   CLEAR_REPLAY  去掉回看
CREATE TABLE live_ops_change (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    room_id uuid NOT NULL REFERENCES live_room(id) ON DELETE RESTRICT,
    action varchar(16) NOT NULL CHECK (action IN ('FORCE_END', 'SET_REPLAY', 'CLEAR_REPLAY')),
    summary varchar(600) NOT NULL,
    operator_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    operator_name varchar(80) NOT NULL,
    changed_at timestamptz NOT NULL
);

CREATE INDEX idx_live_ops_change_room ON live_ops_change (room_id, changed_at DESC, id DESC);

-- 运营端列表：按状态（直播中、预告、已结束）和开播时间排。
CREATE INDEX idx_live_room_ops_list ON live_room (status, scheduled_at DESC, id);
