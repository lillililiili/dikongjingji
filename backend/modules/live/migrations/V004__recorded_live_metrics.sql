-- Room counters from the prototype also contain sample viewers, likes and messages.
-- Reconstruct the independent production counts from persisted viewer and actor actions.
ALTER TABLE live_room
    ADD COLUMN recorded_viewer_count bigint NOT NULL DEFAULT 0 CHECK (recorded_viewer_count >= 0),
    ADD COLUMN recorded_like_count bigint NOT NULL DEFAULT 0 CHECK (recorded_like_count >= 0),
    ADD COLUMN recorded_comment_count bigint NOT NULL DEFAULT 0 CHECK (recorded_comment_count >= 0);

UPDATE live_room r SET
    recorded_viewer_count = (SELECT count(*) FROM live_room_viewer v WHERE v.room_id = r.id),
    recorded_like_count = (SELECT coalesce(sum(w.like_count), 0) FROM live_like_window w WHERE w.room_id = r.id),
    recorded_comment_count = (SELECT count(*) FROM live_comment c WHERE c.room_id = r.id AND c.author_id IS NOT NULL);
