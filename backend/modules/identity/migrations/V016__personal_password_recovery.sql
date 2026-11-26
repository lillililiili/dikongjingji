ALTER TABLE identity_sms_code DROP CONSTRAINT identity_sms_code_purpose_check;
ALTER TABLE identity_sms_code ADD CONSTRAINT identity_sms_code_purpose_check
    CHECK (purpose IN ('LOGIN', 'VERIFY_CURRENT', 'BIND_NEW', 'EXPORT', 'CHANGE_TICKET', 'SIGNUP',
                       'PASSWORD_SETUP', 'PASSWORD_RESET'));
ALTER TABLE identity_sms_code DROP CONSTRAINT identity_sms_code_setup_session_id_fkey;
ALTER TABLE identity_sms_code ADD CONSTRAINT identity_sms_code_setup_session_id_fkey
    FOREIGN KEY (setup_session_id) REFERENCES identity_login_session(id) ON DELETE SET NULL;
CREATE INDEX idx_identity_sms_setup_session ON identity_sms_code (setup_session_id) WHERE setup_session_id IS NOT NULL;

ALTER TABLE identity_login_session DROP CONSTRAINT identity_login_session_revoke_reason_check;
ALTER TABLE identity_login_session ADD CONSTRAINT identity_login_session_revoke_reason_check
    CHECK (revoke_reason IN ('SIGNED_OUT', 'REMOTE', 'REPLACED', 'PHONE_CHANGED', 'CLOSED', 'PASSWORD_CHANGED'));
