-- 自助入驻的首次请求签名，不保存口令或短信验证码。既有 V012 记录保留空值。
ALTER TABLE identity_organization
    ADD COLUMN signup_request_signature varchar(64)
        CHECK (signup_request_signature ~ '^[0-9a-f]{64}$');
