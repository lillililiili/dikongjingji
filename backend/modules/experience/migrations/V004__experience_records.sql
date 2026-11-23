-- S5-4 线上体验记录（docs/redesign/08-后续开发计划.md §5 D7；契约 contracts/openapi-experience.yaml 1.4.0）。
-- 虚拟仿真用 App 内的两个任务模板（无人机起降与悬停、机器人避障），不接外部仿真平台；
-- 软件试用和课程试听记开始时间和到期时间，试用时长写在体验项目上。记录只记登录账号自己的。
-- identity_user 由身份模块创建，本模块排在其后（ModuleFlywayConfiguration）。

-- 仿真项目用哪个 App 内模板；试用、试听项目的试用时长（分钟）。都只对对应类型有意义。
ALTER TABLE experience_item
    ADD COLUMN simulation_template varchar(24)
        CHECK (simulation_template IN ('ROBOT_OBSTACLE', 'DRONE_HOVER')),
    ADD COLUMN trial_minutes integer CHECK (trial_minutes BETWEEN 1 AND 43200),
    ADD CONSTRAINT ck_experience_item_template_type
        CHECK (simulation_template IS NULL OR type = 'SIMULATION'),
    ADD CONSTRAINT ck_experience_item_trial_type
        CHECK (trial_minutes IS NULL OR type IN ('SOFTWARE_TRIAL', 'COURSE_TRIAL'));

CREATE TABLE experience_record (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL REFERENCES identity_user(id) ON DELETE RESTRICT,
    experience_id uuid NOT NULL REFERENCES experience_item(id) ON DELETE RESTRICT,
    -- SIMULATION：一次仿真任务的成绩（App 在本机算出步骤结果，服务端校验范围并按规则算分）；
    -- TRIAL：一次软件试用或课程试听，开始即记，到期时间 = 开始 + 项目的试用时长。
    kind varchar(16) NOT NULL CHECK (kind IN ('SIMULATION', 'TRIAL')),
    started_at timestamptz NOT NULL,
    -- 仿真：结束时间；试用：用户主动结束的时间（到期结束不落库，按时间算）。
    ended_at timestamptz,
    expires_at timestamptz,
    -- 仿真成绩：COMPLETED 全部关卡完成，UNFINISHED 中途退出。
    outcome varchar(16) CHECK (outcome IN ('COMPLETED', 'UNFINISHED')),
    score smallint CHECK (score BETWEEN 0 AND 100),
    duration_seconds integer CHECK (duration_seconds BETWEEN 1 AND 7200),
    levels_completed smallint CHECK (levels_completed >= 0),
    levels_total smallint CHECK (levels_total BETWEEN 1 AND 10),
    mistakes smallint CHECK (mistakes BETWEEN 0 AND 999),
    -- 仿真成绩的提交按幂等键去重（游客在本机玩、登录后补交时同一条只记一次）；试用没有。
    idempotency_key uuid,
    request_hash char(64),
    -- 行状态：以后运营删除记录时改成 DELETED，读接口只给 ACTIVE 的。
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DELETED')),
    version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (account_id, idempotency_key),
    CONSTRAINT ck_experience_record_simulation CHECK (
        kind <> 'SIMULATION' OR (
            ended_at IS NOT NULL AND ended_at >= started_at AND expires_at IS NULL
            AND outcome IS NOT NULL AND score IS NOT NULL AND duration_seconds IS NOT NULL
            AND levels_completed IS NOT NULL AND levels_total IS NOT NULL AND mistakes IS NOT NULL
            AND levels_completed <= levels_total
            AND (outcome = 'COMPLETED') = (levels_completed = levels_total)
            AND idempotency_key IS NOT NULL AND request_hash IS NOT NULL
        )
    ),
    CONSTRAINT ck_experience_record_trial CHECK (
        kind <> 'TRIAL' OR (
            expires_at IS NOT NULL AND expires_at > started_at
            AND (ended_at IS NULL OR (ended_at >= started_at AND ended_at <= expires_at))
            AND outcome IS NULL AND score IS NULL AND duration_seconds IS NULL
            AND levels_completed IS NULL AND levels_total IS NULL AND mistakes IS NULL
            AND idempotency_key IS NULL AND request_hash IS NULL
        )
    )
);

CREATE INDEX idx_experience_record_account ON experience_record (account_id, started_at DESC, id DESC);
CREATE INDEX idx_experience_record_account_item ON experience_record (account_id, experience_id, started_at DESC);
