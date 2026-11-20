-- R6-4（docs/redesign/06-原型对齐修正计划.md §3）：机构的资质审核通过时间。
-- 由平台运营（或开发种子）在线下核对营业执照、行业资质后写入；没有审核页面，也没有审核流程。
-- 为空表示还没审核通过；各业务页面据此显示“资质已审核”，不另存标记。只加列，不改已有数据。
ALTER TABLE identity_organization
    ADD COLUMN qualification_reviewed_at timestamptz;
