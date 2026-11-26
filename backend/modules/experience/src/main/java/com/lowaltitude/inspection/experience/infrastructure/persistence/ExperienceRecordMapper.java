package com.lowaltitude.inspection.experience.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.experience.application.ExperienceRecordRows.RecordRow;
import com.lowaltitude.inspection.experience.application.ExperienceRecordRows.RecordableItemRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 线上体验记录（S5-4）。所有条件都用绑定参数；记录的读写都以 account_id 为条件，别人的记录当作不存在。
 * “当前时间”由调用方传入。
 */
public interface ExperienceRecordMapper {

    /** 记录要用的项目配置；不管状态，由服务判断能不能记。 */
    @Select("""
            SELECT id experience_id,
                   type,
                   title,
                   status,
                   simulation_template,
                   trial_minutes
            FROM experience_item
            WHERE id = #{experienceId}
            FOR SHARE
            """)
    RecordableItemRow findItem(@Param("experienceId") UUID experienceId);

    @Select(RECORD_COLUMNS + """
            WHERE r.account_id = #{accountId}
              AND r.status = 'ACTIVE'
              AND r.idempotency_key = #{idempotencyKey}
            """)
    RecordRow findRecordByKey(@Param("accountId") UUID accountId, @Param("idempotencyKey") UUID idempotencyKey);

    /** 同一个账号的同一个幂等键只插一次；并发重放时由唯一约束挡住，调用方再按键读回。 */
    @Insert("""
            INSERT INTO experience_record (
                id, account_id, experience_id, kind, started_at, ended_at,
                outcome, score, duration_seconds, levels_completed, levels_total, mistakes,
                idempotency_key, request_hash, created_at, updated_at
            ) VALUES (
                #{id}, #{accountId}, #{experienceId}, 'SIMULATION', #{startedAt}, #{endedAt},
                #{outcome}, #{score}, #{durationSeconds}, #{levelsCompleted}, #{levelsTotal}, #{mistakes},
                #{idempotencyKey}, #{requestHash}, #{at}, #{at}
            )
            ON CONFLICT (account_id, idempotency_key) DO NOTHING
            """)
    int insertSimulationRecord(
            @Param("id") UUID id,
            @Param("accountId") UUID accountId,
            @Param("experienceId") UUID experienceId,
            @Param("startedAt") OffsetDateTime startedAt,
            @Param("endedAt") OffsetDateTime endedAt,
            @Param("outcome") String outcome,
            @Param("score") int score,
            @Param("durationSeconds") int durationSeconds,
            @Param("levelsCompleted") int levelsCompleted,
            @Param("levelsTotal") int levelsTotal,
            @Param("mistakes") int mistakes,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("at") OffsetDateTime at
    );

    /** 同一个账号同一个项目开始试用时排队，免得两台手机同时点出两条试用中的记录。事务结束自动释放。 */
    @Select("""
            SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(#{lockKey}, 0))) locked
            """)
    Integer lockTrialStart(@Param("lockKey") String lockKey);

    /** 还在试用中的那一条（没主动结束、没到期）。 */
    @Select(RECORD_COLUMNS + """
            WHERE r.account_id = #{accountId}
              AND r.status = 'ACTIVE'
              AND r.experience_id = #{experienceId}
              AND r.kind = 'TRIAL'
              AND r.ended_at IS NULL
              AND r.expires_at > #{now}
            ORDER BY r.started_at DESC, r.id DESC
            LIMIT 1
            """)
    RecordRow findActiveTrial(
            @Param("accountId") UUID accountId,
            @Param("experienceId") UUID experienceId,
            @Param("now") OffsetDateTime now
    );

    @Insert("""
            INSERT INTO experience_record (
                id, account_id, experience_id, kind, started_at, expires_at, created_at, updated_at
            ) VALUES (
                #{id}, #{accountId}, #{experienceId}, 'TRIAL', #{at}, #{expiresAt}, #{at}, #{at}
            )
            """)
    int insertTrial(
            @Param("id") UUID id,
            @Param("accountId") UUID accountId,
            @Param("experienceId") UUID experienceId,
            @Param("at") OffsetDateTime at,
            @Param("expiresAt") OffsetDateTime expiresAt
    );

    @Select(RECORD_COLUMNS + """
            WHERE r.account_id = #{accountId}
              AND r.status = 'ACTIVE'
              AND r.id = #{recordId}
            """)
    RecordRow findRecord(@Param("accountId") UUID accountId, @Param("recordId") UUID recordId);

    @Select(RECORD_COLUMNS + """
            WHERE r.account_id = #{accountId}
              AND r.status = 'ACTIVE'
              AND r.id = #{recordId}
            FOR UPDATE OF r
            """)
    RecordRow lockRecord(@Param("accountId") UUID accountId, @Param("recordId") UUID recordId);

    /** 只结束还在试用中的；已结束、已到期的不动。 */
    @Update("""
            UPDATE experience_record
            SET ended_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE account_id = #{accountId}
              AND id = #{recordId}
              AND status = 'ACTIVE'
              AND kind = 'TRIAL'
              AND ended_at IS NULL
              AND expires_at > #{at}
            """)
    int endTrial(
            @Param("accountId") UUID accountId,
            @Param("recordId") UUID recordId,
            @Param("at") OffsetDateTime at
    );

    /** experienceId 为 'ALL' 表示不限项目。 */
    @Select("""
            SELECT count(*)
            FROM experience_record r
            WHERE r.account_id = #{accountId}
              AND r.status = 'ACTIVE'
              AND (#{experienceId} = 'ALL' OR r.experience_id = CAST(NULLIF(#{experienceId}, 'ALL') AS uuid))
            """)
    long countRecords(@Param("accountId") UUID accountId, @Param("experienceId") String experienceId);

    @Select(RECORD_COLUMNS + """
            WHERE r.account_id = #{accountId}
              AND r.status = 'ACTIVE'
              AND (#{experienceId} = 'ALL' OR r.experience_id = CAST(NULLIF(#{experienceId}, 'ALL') AS uuid))
            ORDER BY r.started_at DESC, r.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<RecordRow> findRecords(
            @Param("accountId") UUID accountId,
            @Param("experienceId") String experienceId,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /** 列的顺序就是 RecordRow 构造参数的顺序。 */
    String RECORD_COLUMNS = """
            SELECT r.id record_id,
                   r.experience_id,
                   r.item_snapshot->>'type' AS type,
                   r.item_snapshot->>'title' AS title,
                   r.item_snapshot->>'coverUrl' AS cover_url,
                   (r.item_snapshot->>'productId')::uuid AS product_id,
                   r.item_snapshot->>'simulationTemplate' AS simulation_template,
                   r.kind,
                   r.started_at,
                   r.ended_at,
                   r.expires_at,
                   r.outcome,
                   r.score,
                   r.duration_seconds,
                   r.levels_completed,
                   r.levels_total,
                   r.mistakes,
                   r.request_hash
            FROM experience_record r
            """;
}
