package com.lowaltitude.inspection.experience.infrastructure.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Select;

public interface PlayGameMapper {
    @Select("""
            SELECT id, title, subtitle, skill_label, tone, avatar, board
            FROM experience_play_game WHERE published ORDER BY sort_order, id
            """)
    List<Game> listPublished();

    record Game(String id, String title, String subtitle, String skillLabel, String tone, String avatar, String board) {}
}
