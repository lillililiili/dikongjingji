-- S3-1 运营端“举报与反馈”（docs/redesign/08-后续开发计划.md §2 S3-1，契约 contracts/openapi-ops-feedback.yaml 1.0.0）。
-- 反馈有了处理状态：PENDING 待处理、HANDLED 已处理（写处理说明、处理人、时间），由平台客服在运营端处理，只处理一次。
-- 举报（发现页视频评论、直播弹幕）也记在反馈里，source 标出来源，target_id 是被举报的评论或弹幕，
-- target_parent_id 是它所在的视频或直播间，运营端据此调用内容、直播模块已有的受权隐藏接口。
-- 以前的举报没记对象，一律当作用户反馈（USER）。只加不改已有数据。
ALTER TABLE support_feedback
    ADD COLUMN source varchar(24) NOT NULL DEFAULT 'USER'
        CHECK (source IN ('USER', 'VIDEO_COMMENT_REPORT', 'LIVE_COMMENT_REPORT')),
    ADD COLUMN target_id uuid,
    ADD COLUMN target_parent_id uuid,
    ADD COLUMN handle_status varchar(16) NOT NULL DEFAULT 'PENDING'
        CHECK (handle_status IN ('PENDING', 'HANDLED')),
    ADD COLUMN handle_note varchar(200),
    ADD COLUMN handled_at timestamptz,
    ADD COLUMN handled_by uuid REFERENCES identity_user(id) ON DELETE RESTRICT,
    ADD COLUMN handled_by_name varchar(80),
    ADD COLUMN version integer NOT NULL DEFAULT 0 CHECK (version >= 0),
    ADD CONSTRAINT ck_support_feedback_target
        CHECK ((source = 'USER') = (target_id IS NULL) AND (target_id IS NULL) = (target_parent_id IS NULL)),
    ADD CONSTRAINT ck_support_feedback_handled
        CHECK ((handle_status = 'HANDLED') = (handled_at IS NOT NULL)
            AND (handled_at IS NULL) = (handled_by IS NULL)
            AND (handled_at IS NULL) = (handled_by_name IS NULL)
            AND (handled_at IS NULL) = (handle_note IS NULL));

CREATE INDEX idx_support_feedback_handle
    ON support_feedback (handle_status, created_at DESC, id DESC);
