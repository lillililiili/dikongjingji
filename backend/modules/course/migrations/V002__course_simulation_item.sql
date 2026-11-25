-- 试听页关联的仿真独立于体验中心进入课程的 experience_item_id；旧课程保留空关联。
ALTER TABLE course
    ADD COLUMN simulation_item_id uuid,
    ADD CONSTRAINT course_simulation_online_only CHECK (simulation_item_id IS NULL OR delivery_mode = 'ONLINE');
