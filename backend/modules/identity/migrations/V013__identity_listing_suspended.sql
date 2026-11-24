-- 未通过资质审核的机构不能上架、已上架的在 App 不可见（2026-11-24，招标演示第 5 条）。
-- listing_suspended 为 true：这家机构的商品和服务不能上架；已经上架的，公开读取（列表、详情、购物车、下单、预约、报名）一律当作
-- 没有。已成交的订单、履约、凭证、核销不看这一列。
-- 谁来改：运营端新建经营机构、商家自助入驻时为 true；资质资料或附件改动回到待审核、审核驳回时为 true；审核通过时为 false；
-- 批量导入按有没有审核时间。企业客户（CUSTOMER）和平台机构（PLATFORM）不上架商品和服务，一直是 false。
-- 不等于“没有审核时间”：没有资质记录、以前线下开通的机构默认 false，不受影响，等录了资质再按上面的规则。
ALTER TABLE identity_organization
    ADD COLUMN listing_suspended boolean NOT NULL DEFAULT false;

-- 回填：有资质记录、但没审核通过（待审核或已驳回）的经营机构。
UPDATE identity_organization o
SET listing_suspended = true
FROM identity_organization_qualification q
WHERE q.organization_id = o.id
  AND q.status <> 'APPROVED'
  AND o.organization_type = 'PROVIDER';

-- 各模块的公开读取按“这家机构是不是被挂起”排除，挂起的只是少数。
CREATE INDEX idx_identity_organization_listing_suspended
    ON identity_organization (id)
    WHERE listing_suspended;
