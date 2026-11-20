-- M20 平台内直播（docs/redesign/07-视频与直播实现计划.md §4 设计假设 1）：直播主办角色。
-- 平台给机构开通直播权限：把这个角色授给机构的操作员（限定在该机构），才能建直播间、改预告、设讲解清单、隐藏本机构
-- 直播间的弹幕。不带任何巡检权限；没有开通的机构建不了直播间。
INSERT INTO identity_role (role_code, role_name, scope_type)
VALUES ('LIVE_HOST', '直播主办', 'ORGANIZATION')
ON CONFLICT (role_code) DO NOTHING;
