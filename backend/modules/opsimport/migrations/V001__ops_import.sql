-- S2-3 首批资源的批量导入（运营端，契约 contracts/openapi-ops-import.yaml 1.0.0，填表规则 docs/ops/首批资源导入.md）。
-- 一次预检建一个批次：存文件的摘要和存储键（文件本身在私有存储，保留 180 天）、逐行问题；导入时记下改了哪些对象。
-- 各类编号（product_ref、sku_ref 等）和生成的 ID 的对照表跨批次共用：第二次导入同一编号是更新，不是新建。
-- identity_user 由身份迁移创建，本模块排在它之后（ModuleFlywayConfiguration）。业务数据由各模块自己的导入端口写，
-- 这里不引用别的模块的业务表。

CREATE TABLE ops_import_batch (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    kind varchar(24) NOT NULL CHECK (kind IN (
        'ORGANIZATIONS', 'MERCHANT_ACCOUNTS', 'MALL', 'ACTIVITIES', 'VENUES', 'SERVICES', 'CONTENT')),
    status varchar(16) NOT NULL CHECK (status IN ('CHECKED', 'REJECTED', 'IMPORTED')),
    -- 这组文件按文件名排好后的摘要：同一类、同样内容再导入时据此认出来，不重复写。
    file_sha256 char(64) NOT NULL,
    row_count integer NOT NULL CHECK (row_count >= 0),
    issue_count integer NOT NULL CHECK (issue_count >= 0),
    change_count integer NOT NULL DEFAULT 0 CHECK (change_count >= 0),
    -- 内容和以前导入过的某个批次完全一样时指向那个批次（这次什么都不写）。
    duplicate_of uuid REFERENCES ops_import_batch(id) ON DELETE RESTRICT,
    checked_by uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    checked_by_name varchar(80) NOT NULL,
    checked_at timestamptz NOT NULL,
    imported_by uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    imported_by_name varchar(80),
    imported_at timestamptz,
    retained_until timestamptz NOT NULL,
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT ck_ops_import_batch_imported CHECK (
        (status = 'IMPORTED') = (imported_at IS NOT NULL)
        AND (imported_at IS NULL) = (imported_by IS NULL)
        AND (imported_at IS NULL) = (imported_by_name IS NULL)),
    CONSTRAINT ck_ops_import_batch_rejected CHECK ((status = 'REJECTED') = (issue_count > 0))
);

-- 同一类同样内容真正写入的只有一个批次（重复的那些 duplicate_of 指向它）。
CREATE UNIQUE INDEX uq_ops_import_batch_imported
    ON ops_import_batch (kind, file_sha256)
    WHERE status = 'IMPORTED' AND duplicate_of IS NULL;

CREATE INDEX idx_ops_import_batch_checked ON ops_import_batch (checked_at DESC, id DESC);
CREATE INDEX idx_ops_import_batch_kind_checked ON ops_import_batch (kind, checked_at DESC, id DESC);

-- 批次里的文件：名称、行数、大小、摘要和私有存储的键（不对外给地址）。
CREATE TABLE ops_import_file (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id uuid NOT NULL REFERENCES ops_import_batch(id) ON DELETE RESTRICT,
    file_name varchar(120) NOT NULL,
    row_count integer NOT NULL CHECK (row_count >= 0),
    size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    sha256 char(64) NOT NULL,
    storage_key varchar(200) NOT NULL UNIQUE,
    sort_order integer NOT NULL,
    UNIQUE (batch_id, file_name)
);

-- 预检发现的问题（最多存 500 条，总数在 issue_count）。
CREATE TABLE ops_import_issue (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id uuid NOT NULL REFERENCES ops_import_batch(id) ON DELETE RESTRICT,
    sort_order integer NOT NULL,
    file_name varchar(120) NOT NULL,
    line integer NOT NULL CHECK (line >= 0),
    column_name varchar(64),
    message varchar(300) NOT NULL
);

CREATE INDEX idx_ops_import_issue_batch ON ops_import_issue (batch_id, sort_order);

-- 导入时改了哪些对象（留痕）：编号、生成或沿用的 ID、新建还是更新。
CREATE TABLE ops_import_change (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id uuid NOT NULL REFERENCES ops_import_batch(id) ON DELETE RESTRICT,
    sort_order integer NOT NULL,
    ref_kind varchar(24) NOT NULL CHECK (ref_kind IN (
        'ORGANIZATION', 'OPERATOR', 'MERCHANT_ACCOUNT', 'PRODUCT', 'SKU', 'OFFER')),
    reference varchar(200) NOT NULL,
    target_id uuid NOT NULL,
    change_type varchar(8) NOT NULL CHECK (change_type IN ('CREATED', 'UPDATED'))
);

CREATE INDEX idx_ops_import_change_batch ON ops_import_change (batch_id, sort_order);

-- 编号对照表：同一类里一个编号对应一个对象，跨批次共用。供给的编号是“规格编号|商家编码|城市编码（全国为 *）”。
CREATE TABLE ops_import_ref (
    ref_kind varchar(24) NOT NULL CHECK (ref_kind IN ('PRODUCT', 'SKU', 'OFFER')),
    reference varchar(200) NOT NULL,
    target_id uuid NOT NULL,
    first_batch_id uuid NOT NULL REFERENCES ops_import_batch(id) ON DELETE RESTRICT,
    last_batch_id uuid NOT NULL REFERENCES ops_import_batch(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (ref_kind, reference)
);
