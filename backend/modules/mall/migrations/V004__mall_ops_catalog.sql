-- S3-1 运营端“商品与供给”和 S4-4 要补的商品字段（docs/redesign/08-后续开发计划.md §2，契约 contracts/openapi-ops-catalog.yaml
-- 1.0.0、contracts/openapi-mall-catalog.yaml 1.3.0）。平台运营在 Web 运营端建商品、改资料、管规格和在售供给、上下架、改价。
-- 只加表和列，不改已有数据：老商品没有卖点、图集、图文，供给没有库存（空着表示不限）。

-- 卖点标签：App 商品详情名称下面那一行（原型 PdFull 的“图形化编程 / 避障练习”）。每个商品最多 5 个，整份替换。
CREATE TABLE mall_product_selling_point (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES mall_product(id) ON DELETE RESTRICT,
    label varchar(12) NOT NULL CHECK (length(btrim(label)) > 0),
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_mall_product_selling_point_product ON mall_product_selling_point (product_id, sort_order, id);

-- 图集：封面之后的商品图，按顺序。只存受控地址（平台对象存储返回的地址或 https 地址）。最多 9 张，整份替换。
CREATE TABLE mall_product_image (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES mall_product(id) ON DELETE RESTRICT,
    url varchar(500) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_mall_product_image_product ON mall_product_image (product_id, sort_order, id);

-- 图文介绍：一段文字或一张图，按顺序。最多 30 段，整份替换。
CREATE TABLE mall_product_intro_block (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES mall_product(id) ON DELETE RESTRICT,
    block_type varchar(8) NOT NULL CHECK (block_type IN ('TEXT', 'IMAGE')),
    text varchar(1000),
    image_url varchar(500),
    sort_order integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_mall_intro_block_content CHECK (
        (block_type = 'TEXT' AND text IS NOT NULL AND image_url IS NULL)
        OR (block_type = 'IMAGE' AND image_url IS NOT NULL))
);

CREATE INDEX idx_mall_product_intro_block_product ON mall_product_intro_block (product_id, sort_order, id);

-- 运营端新建商品、新建供给的幂等键：同一个键重复提交只建一个。
ALTER TABLE mall_product
    ADD COLUMN ops_create_key uuid;

CREATE UNIQUE INDEX uq_mall_product_ops_create_key ON mall_product (ops_create_key) WHERE ops_create_key IS NOT NULL;
CREATE INDEX idx_mall_product_updated ON mall_product (updated_at DESC, id DESC);

-- 库存：这家商家在这个城市这个规格的现货件数。空着表示不限、不显示；0 表示暂时缺货（App 不能选、购物车拒绝）。
-- 这一版不在下单时自动扣减，由运营或以后的商家工作台改数。
ALTER TABLE mall_offer
    ADD COLUMN stock_quantity integer CHECK (stock_quantity IS NULL OR stock_quantity >= 0),
    ADD COLUMN ops_create_key uuid;

CREATE UNIQUE INDEX uq_mall_offer_ops_create_key ON mall_offer (ops_create_key) WHERE ops_create_key IS NOT NULL;

-- 同一个规格、商家、城市（空着算“全国”）只能有一条供给（老数据本来就是这样，运营端和导入都靠它判断重复）。
CREATE UNIQUE INDEX uq_mall_offer_sku_merchant_city
    ON mall_offer (sku_id, merchant_id, COALESCE(city_code, '*'));

-- 运营端上传的商品图片元数据。文件在对象存储（开发环境是本机目录，见 bootstrap 的 LocalMallMediaStore），
-- 这里存存储键、类型、大小、摘要和对外的受控地址。
CREATE TABLE mall_media_asset (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    storage_key varchar(200) NOT NULL UNIQUE,
    content_type varchar(16) NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    size_bytes bigint NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 5242880),
    sha256 char(64) NOT NULL,
    url varchar(500) NOT NULL,
    uploaded_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    uploaded_at timestamptz NOT NULL
);

-- 运营端对商品做过的每一步（留痕）：建、改资料、上下架、规格、供给、改价（从多少改成多少）、批量导入。只追加。
-- detail 是给人看的说明，不含联系方式。
CREATE TABLE mall_catalog_operation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES mall_product(id) ON DELETE RESTRICT,
    action varchar(24) NOT NULL CHECK (action IN (
        'PRODUCT_CREATE', 'PRODUCT_UPDATE', 'PRODUCT_PUBLISH', 'PRODUCT_UNPUBLISH', 'SKU_CREATE', 'SKU_UPDATE',
        'OFFER_CREATE', 'OFFER_UPDATE', 'OFFER_PRICE_CHANGE', 'OFFER_ACTIVATE', 'OFFER_DEACTIVATE', 'IMPORT')),
    detail varchar(500) NOT NULL,
    operator_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    operator_name varchar(80) NOT NULL,
    occurred_at timestamptz NOT NULL
);

CREATE INDEX idx_mall_catalog_operation_product ON mall_catalog_operation (product_id, occurred_at DESC, id DESC);
