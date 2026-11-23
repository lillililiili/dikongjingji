package com.lowaltitude.inspection.favorite.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 收藏的读写（计划 08 S5-6）。全部用绑定参数，每条语句都以账号为条件。“当前时间”由调用方传入。
 * 没有删除语句：取消收藏只记 removed_at，好让 10 分钟内的撤销恢复原来那条。
 */
public interface FavoriteMapper {

    /** 同一个账号的收藏写入排队：上限检查和写入之间不会插进同一个人的另一次收藏。 */
    @Select("""
            SELECT 1 FROM (
                SELECT pg_advisory_xact_lock(hashtextextended('favorite-account:' || CAST(#{accountId} AS text), 0))
            ) locked
            """)
    Integer lockAccount(@Param("accountId") UUID accountId);

    String ITEM_COLUMNS = """
            SELECT f.target_type, f.target_id, f.title, f.favorited_at, f.removed_at
            FROM favorite_item f
            """;

    /** 这个账号对这个对象的那一条（取消了的也返回）。 */
    @Select(ITEM_COLUMNS + """
            WHERE f.account_id = #{accountId} AND f.target_type = #{targetType} AND f.target_id = #{targetId}
            """)
    ItemRow findItem(
            @Param("accountId") UUID accountId,
            @Param("targetType") String targetType,
            @Param("targetId") UUID targetId
    );

    /** 第一次收藏；已经有这一条时什么都不写（唯一约束）。 */
    @Insert("""
            INSERT INTO favorite_item (account_id, target_type, target_id, title, favorited_at, created_at, updated_at)
            VALUES (#{accountId}, #{targetType}, #{targetId}, #{title}, #{at}, #{at}, #{at})
            ON CONFLICT (account_id, target_type, target_id) DO NOTHING
            """)
    int insertItem(
            @Param("accountId") UUID accountId,
            @Param("targetType") String targetType,
            @Param("targetId") UUID targetId,
            @Param("title") String title,
            @Param("at") OffsetDateTime at
    );

    /** 取消过的重新收藏：收藏时间和标题都换成现在的。 */
    @Update("""
            UPDATE favorite_item
            SET removed_at = NULL, title = #{title}, favorited_at = #{at}, version = version + 1, updated_at = #{at}
            WHERE account_id = #{accountId} AND target_type = #{targetType} AND target_id = #{targetId}
              AND removed_at IS NOT NULL
            """)
    int refavorite(
            @Param("accountId") UUID accountId,
            @Param("targetType") String targetType,
            @Param("targetId") UUID targetId,
            @Param("title") String title,
            @Param("at") OffsetDateTime at
    );

    /** 撤销刚才的取消：收藏时间和标题不变。 */
    @Update("""
            UPDATE favorite_item
            SET removed_at = NULL, version = version + 1, updated_at = #{at}
            WHERE account_id = #{accountId} AND target_type = #{targetType} AND target_id = #{targetId}
              AND removed_at IS NOT NULL
            """)
    int restore(
            @Param("accountId") UUID accountId,
            @Param("targetType") String targetType,
            @Param("targetId") UUID targetId,
            @Param("at") OffsetDateTime at
    );

    @Update("""
            UPDATE favorite_item
            SET removed_at = #{at}, version = version + 1, updated_at = #{at}
            WHERE account_id = #{accountId} AND target_type = #{targetType} AND target_id = #{targetId}
              AND removed_at IS NULL
            """)
    int remove(
            @Param("accountId") UUID accountId,
            @Param("targetType") String targetType,
            @Param("targetId") UUID targetId,
            @Param("at") OffsetDateTime at
    );

    @Select("SELECT count(*) FROM favorite_item WHERE account_id = #{accountId} AND removed_at IS NULL")
    long countActive(@Param("accountId") UUID accountId);

    /** 每类对象还在收藏的条数（没有的类型不出现）。 */
    @Select("""
            SELECT target_type, count(*) AS item_count
            FROM favorite_item
            WHERE account_id = #{accountId} AND removed_at IS NULL
            GROUP BY target_type
            """)
    List<TypeCountRow> countByType(@Param("accountId") UUID accountId);

    /** 一个分组的一页（一个分组最多两类对象：只有一类时两个参数相同），新收藏的在前。 */
    @Select(ITEM_COLUMNS + """
            WHERE f.account_id = #{accountId} AND f.removed_at IS NULL
              AND f.target_type IN (#{firstType}, #{secondType})
            ORDER BY f.favorited_at DESC, f.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ItemRow> findPage(
            @Param("accountId") UUID accountId,
            @Param("firstType") String firstType,
            @Param("secondType") String secondType,
            @Param("limit") int limit,
            @Param("offset") long offset
    );

    record ItemRow(String targetType, UUID targetId, String title, OffsetDateTime favoritedAt,
                   OffsetDateTime removedAt) {
    }

    record TypeCountRow(String targetType, long itemCount) {
    }
}
