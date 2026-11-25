package com.lowaltitude.inspection.experience.application.ops;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.ops.OpsActor;
import com.lowaltitude.inspection.ops.common.OpsError;
import com.lowaltitude.inspection.ops.common.OpsException;
import com.lowaltitude.inspection.ops.common.OpsText;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class PlayGameOpsService {
    private static final String SELECT = """
            SELECT d.id,d.title,d.subtitle,d.skill_label,d.tone,d.avatar,d.board,d.sort_order,d.version,
                   d.published_version,coalesce(p.published,false) AS published
            FROM experience_play_game_draft d LEFT JOIN experience_play_game p ON p.id=d.id
            """;
    private final JdbcClient database;

    public PlayGameOpsService(JdbcClient database) { this.database = database; }

    public record Input(String title, String subtitle, String skillLabel, String tone, String avatar,
                        String board, Integer sortOrder, Integer version) {}
    public record Game(String id, String title, String subtitle, String skillLabel, String tone, String avatar,
                       String board, int sortOrder, int version, Integer publishedVersion, boolean published) {}
    public record Change(int version, String action, String operatorName, OffsetDateTime changedAt) {}

    enum Failure implements OpsError {
        ABSENT(404, OpsError.NOT_FOUND, "关卡不存在"),
        STALE(409, OpsError.VERSION_CONFLICT, "关卡已被修改，请刷新后重试"),
        OFFLINE(409, "OPS_GAME_ALREADY_OFFLINE", "关卡已下架"),
        BOARD(422, "OPS_GAME_BOARD_INVALID", "棋盘尚不能通关");
        private final int status;
        private final String code;
        private final String message;
        Failure(int status, String code, String message) { this.status=status; this.code=code; this.message=message; }
        public int httpStatus() { return status; }
        public String code() { return code; }
        public String message() { return message; }
    }

    public List<Game> list() {
        return database.sql(SELECT + " ORDER BY d.sort_order,d.id").query(Game.class).list();
    }
    public List<Change> changes(String id) {
        return database.sql("""
                SELECT version,action,operator_name,changed_at FROM experience_play_game_change
                WHERE game_id=? ORDER BY version DESC
                """).param(id).query(Change.class).list();
    }

    @Transactional
    public Game save(String id, Input input, OpsActor actor) {
        final boolean create = id == null;
        final String key = create ? "level-" + UUID.randomUUID() : id;
        if (!create) lock(key,input.version());
        String title = OpsText.required(input.title(),100,"title");
        String description = OpsText.required(input.subtitle(),180,"subtitle");
        String skill = OpsText.required(input.skillLabel(),100,"skillLabel");
        String tone = OpsText.required(input.tone(),12,"tone");
        String avatar = OpsText.required(input.avatar(),12,"avatar");
        if (!Set.of("SKY","VIOLET").contains(tone) || !Set.of("DRONE","ROBOT").contains(avatar)) {
            throw new IllegalArgumentException("无效主题或角色");
        }
        if (input.board() == null || !input.board().matches("[.WSG]{25}")) {
            throw new IllegalArgumentException("棋盘须包含 25 个格子");
        }
        if (input.sortOrder() == null || input.sortOrder() < 0) throw new IllegalArgumentException("顺序不能为负数");
        String sql = create ? """
                INSERT INTO experience_play_game_draft(id,title,subtitle,skill_label,tone,avatar,board,sort_order)
                VALUES(:id,:title,:subtitle,:skill,:tone,:avatar,:board,:sort)
                """ : """
                UPDATE experience_play_game_draft SET title=:title,subtitle=:subtitle,skill_label=:skill,
                    tone=:tone,avatar=:avatar,board=:board,sort_order=:sort,version=version+1 WHERE id=:id
                """;
        database.sql(sql).param("id",key).param("title",title).param("subtitle",description).param("skill",skill)
                .param("tone",tone).param("avatar",avatar).param("board",input.board()).param("sort",input.sortOrder()).update();
        return record(key,create ? "CREATE" : "EDIT",actor);
    }

    @Transactional
    public Game publish(String id, Integer version, OpsActor actor) {
        Game draft = lock(id,version);
        String problem = boardProblem(draft.board());
        if (problem != null) throw new OpsException(Failure.BOARD,Map.of("board",problem));
        database.sql("""
                INSERT INTO experience_play_game(id,title,subtitle,skill_label,tone,avatar,board,sort_order,published)
                SELECT id,title,subtitle,skill_label,tone,avatar,board,sort_order,true
                FROM experience_play_game_draft WHERE id=?
                ON CONFLICT (id) DO UPDATE SET title=EXCLUDED.title,subtitle=EXCLUDED.subtitle,skill_label=EXCLUDED.skill_label,
                    tone=EXCLUDED.tone,avatar=EXCLUDED.avatar,board=EXCLUDED.board,sort_order=EXCLUDED.sort_order,published=true
                """).param(id).update();
        database.sql("UPDATE experience_play_game_draft SET version=version+1,published_version=version+1 WHERE id=?")
                .param(id).update();
        return record(id,"PUBLISH",actor);
    }

    @Transactional
    public Game offline(String id, Integer version, OpsActor actor) {
        if (!lock(id,version).published()) throw new OpsException(Failure.OFFLINE);
        database.sql("UPDATE experience_play_game SET published=false WHERE id=?").param(id).update();
        database.sql("UPDATE experience_play_game_draft SET version=version+1 WHERE id=?").param(id).update();
        return record(id,"OFFLINE",actor);
    }

    private Game lock(String id, Integer expected) {
        if (expected == null || expected < 0) throw new IllegalArgumentException("缺少版本号");
        Game game = database.sql(SELECT+" WHERE d.id=? FOR UPDATE OF d").param(id).query(Game.class).optional()
                .orElseThrow(() -> new OpsException(Failure.ABSENT));
        if (game.version() != expected) throw new OpsException(Failure.STALE);
        return game;
    }

    private Game record(String id, String action, OpsActor actor) {
        Game game = database.sql(SELECT+" WHERE d.id=?").param(id).query(Game.class).single();
        database.sql("""
                INSERT INTO experience_play_game_change(game_id,version,action,operator_id,operator_name) VALUES(?,?,?,?,?)
                """).params(id,game.version(),action,actor.accountId(),actor.displayName()).update();
        return game;
    }

    // Movement is reversible and the goal remains traversable until every star is collected.
    static String boardProblem(String board) {
        if (board == null || !board.matches("[.WSG]{25}")) return "棋盘须为 5 × 5，只能使用空地、障碍、星星和终点";
        if (board.charAt(0) != '.') return "左上角起点必须为空地";
        int goal = board.indexOf('G');
        if (goal < 0 || goal != board.lastIndexOf('G')) return "棋盘必须恰有一个终点";
        if (board.indexOf('S') < 0) return "至少放置一颗星星";
        boolean[] seen = new boolean[25];
        var queue = new ArrayDeque<Integer>();
        seen[0] = true;
        queue.add(0);
        while (!queue.isEmpty()) {
            int current = queue.remove();
            for (int delta : new int[]{-5,5,-1,1}) {
                int next = current + delta;
                if (next < 0 || next >= 25 || (Math.abs(delta)==1 && current/5 != next/5)) continue;
                if (!seen[next] && board.charAt(next) != 'W') {
                    seen[next] = true;
                    queue.add(next);
                }
            }
        }
        if (!seen[goal]) return "终点无法从起点到达";
        for (int i=0; i<25; i++) {
            if (board.charAt(i)=='S' && !seen[i]) return "第 " + (i/5+1) + " 行第 " + (i%5+1) + " 列的星星无法到达";
        }
        return null;
    }
}
