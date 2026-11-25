-- Authored game levels. W = wall, S = star, G = goal; cell 0 is the starting point.
CREATE TABLE experience_play_game (
    id varchar(60) PRIMARY KEY,
    title varchar(100) NOT NULL,
    subtitle varchar(180) NOT NULL,
    skill_label varchar(100) NOT NULL,
    tone varchar(12) NOT NULL CHECK (tone IN ('VIOLET', 'SKY')),
    avatar varchar(12) NOT NULL CHECK (avatar IN ('ROBOT', 'DRONE')),
    board varchar(25) NOT NULL CHECK (board ~ '^[.][.WSG]{24}$'
        AND length(replace(board, 'G', '')) = 24 AND strpos(board, 'S') > 0),
    sort_order integer NOT NULL,
    published boolean NOT NULL DEFAULT false
);

INSERT INTO experience_play_game (id, title, subtitle, skill_label, tone, avatar, board, sort_order, published) VALUES
    ('robot-maze', '机器人迷宫', '转个弯，和小伙伴一起找到出口', '逻辑启蒙 · 避障闯关', 'VIOLET', 'ROBOT', '..S...WWW.S..W..W.W...S.G', 10, true),
    ('drone-route', '无人机寻星', '规划路线，把沿途星星带回基地', '路线规划 · 寻星挑战', 'SKY', 'DRONE', '....S.W.W..WSW....W.S...G', 20, true);
