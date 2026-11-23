-- S4-4 后端补字段（docs/redesign/08-后续开发计划.md §5）：首页推荐“附近场馆”卡片第一行的项目摘要
-- （原型画板 HomeFull：“模拟飞行 · 机器人编程 · 厂家展区”）。
-- 运营在运营端“场馆与设备”里填（契约 contracts/openapi-ops-venue.yaml 1.1.0）；没填时由启用的前三个项目名按排序拼成
-- （契约 contracts/openapi-experience.yaml 1.4.0 的 VenueCard.projectSummary）。
-- V004 留给别的批次。

ALTER TABLE venue ADD COLUMN project_summary varchar(60);
