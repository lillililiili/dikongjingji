-- A fresh SMS sign-in authorizes setting the first password in that session.
-- Existing sessions have no such proof. Changing the phone clears outstanding proofs.
ALTER TABLE identity_login_session ADD COLUMN password_setup_until timestamptz;
ALTER TABLE identity_sms_code ADD COLUMN setup_session_id uuid REFERENCES identity_login_session(id);
ALTER TABLE identity_sms_code DROP CONSTRAINT identity_sms_code_purpose_check;
ALTER TABLE identity_sms_code ADD CONSTRAINT identity_sms_code_purpose_check
    CHECK (purpose IN ('LOGIN', 'VERIFY_CURRENT', 'BIND_NEW', 'EXPORT', 'CHANGE_TICKET', 'SIGNUP', 'PASSWORD_SETUP'));

CREATE TABLE identity_password_guard (
    phone varchar(11) PRIMARY KEY CHECK (phone ~ '^1[3-9][0-9]{9}$'),
    failed_count integer NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    window_started_at timestamptz NOT NULL,
    locked_until timestamptz
);
