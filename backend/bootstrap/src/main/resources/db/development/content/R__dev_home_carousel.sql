-- 仅开发环境的东营示例轮播，依照 2026-11-14 首页画板。图片从开发媒体服务读取。
INSERT INTO content_home_slide
(city_code, id, tag, title_first, title_second, description_first, description_second,
 action_label, image_ref, image_alt, tone, target, status, sort_order)
VALUES
('370500', 'contest', '热门精选 · 赛事报名', '小小飞行家', '挑战大舞台', '青少年无人机编程挑战赛', '东营站 · 8–15 岁',
 '查看赛事 · 去报名', '/api/v1/dev-media/images/home/hero-world.jpg', '无人机与机器人探索场景', 'SKY', 'ACTIVITY', 'PUBLISHED', 10),
('370500', 'study-tour', '周末精选 · 研学报名', '把好奇心', '变成创造力', '机器人研学体验', '动手编程 · 避障闯关',
 '查看研学 · 去报名', '/api/v1/dev-media/images/home/robot-workshop.jpg', '孩子们在明亮课堂探索机器人', 'SUN', 'ACTIVITY', 'PUBLISHED', 20),
('370500', 'games', '趣玩挑战 · 随时开玩', '动动脑筋', '一起闯关', '机器人迷宫 · 无人机寻星', '虚拟小游戏 · 无需设备',
 '开始趣玩挑战', '/api/v1/dev-media/images/home/robot-rover.jpg', '可爱的编程机器人', 'VIOLET', 'PLAY_GAMES', 'PUBLISHED', 30),
('370500', 'experience', '体验精选 · 先试再选', '先体验', '再决定', '线上虚拟仿真 · 录播演示', '也可预约线下场馆',
 '探索体验 · 去预约', '/api/v1/dev-media/images/home/hero-world.jpg', '机器人与无人机的探索乐园', 'MINT', 'EXPERIENCE', 'PUBLISHED', 40)
ON CONFLICT (city_code, id) DO NOTHING;
