-- Keep the public table as the last published snapshot so editing a draft cannot change an active game.
CREATE TABLE experience_play_game_draft (
    id varchar(60) PRIMARY KEY,
    title varchar(100) NOT NULL,
    subtitle varchar(180) NOT NULL,
    skill_label varchar(100) NOT NULL,
    tone varchar(12) NOT NULL CHECK (tone IN ('VIOLET','SKY')),
    avatar varchar(12) NOT NULL CHECK (avatar IN ('ROBOT','DRONE')),
    board varchar(25) NOT NULL CHECK (board ~ '^[.WSG]{25}$'),
    sort_order integer NOT NULL,
    version integer NOT NULL DEFAULT 0,
    published_version integer CHECK (published_version BETWEEN 0 AND version)
);
INSERT INTO experience_play_game_draft(id,title,subtitle,skill_label,tone,avatar,board,sort_order,published_version)
SELECT id,title,subtitle,skill_label,tone,avatar,board,sort_order,CASE WHEN published THEN 0 END
FROM experience_play_game;

CREATE TABLE experience_play_game_change (
    game_id varchar(60) NOT NULL REFERENCES experience_play_game_draft(id),
    version integer NOT NULL,
    action varchar(12) NOT NULL CHECK (action IN ('CREATE','EDIT','PUBLISH','OFFLINE')),
    operator_id uuid NOT NULL REFERENCES identity_user(id),
    operator_name varchar(80) NOT NULL,
    changed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (game_id,version)
);
