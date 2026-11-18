-- 收货地址：登录账号自己的地址簿，确认订单时选一条用于本单。
-- identity_user 和 pgcrypto 由身份模块的迁移创建，本模块排在其后（ModuleFlywayConfiguration）。
-- 收货人姓名、手机号、详细地址按敏感资料处理：所有读写都以 owner_id 为条件，日志里不出现。
-- 地区只到地级市：平台的行政区划目录（platform_city）只有省和地级市，区县写在详细地址里。

CREATE TABLE account_address (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    recipient_name varchar(20) NOT NULL CHECK (btrim(recipient_name) <> ''),
    -- 中国大陆手机号，11 位数字。
    phone varchar(11) NOT NULL CHECK (phone ~ '^1[3-9][0-9]{9}$'),
    -- 保存时的省、市编码和名称（取自平台城市目录），之后目录改名也不影响已存地址的显示。
    province_code varchar(16) NOT NULL,
    province_name varchar(40) NOT NULL,
    city_code varchar(16) NOT NULL,
    city_name varchar(40) NOT NULL,
    detail varchar(80) NOT NULL CHECK (btrim(detail) <> ''),
    is_default boolean NOT NULL DEFAULT false,
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DELETED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- 一个账号最多一条默认地址。
CREATE UNIQUE INDEX uq_account_address_default
    ON account_address (owner_id)
    WHERE is_default AND status = 'ACTIVE';

CREATE INDEX idx_account_address_owner
    ON account_address (owner_id, created_at, id)
    WHERE status = 'ACTIVE';
