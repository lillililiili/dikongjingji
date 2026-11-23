package com.lowaltitude.inspection.opsimport.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 批量导入的批次、文件、问题、改动和编号对照表（迁移 opsimport V001）。全部绑定参数。 */
public interface OpsImportMapper {

    String BATCH_COLUMNS = """
            SELECT b.id, b.kind, b.status, b.file_sha256, b.row_count, b.issue_count, b.change_count, b.duplicate_of,
                   b.checked_by_name, b.checked_at, b.imported_by_name, b.imported_at, b.retained_until
            FROM ops_import_batch b
            """;

    @Insert("""
            INSERT INTO ops_import_batch (id, kind, status, file_sha256, row_count, issue_count, duplicate_of,
                                          checked_by, checked_by_name, checked_at, imported_by, imported_by_name,
                                          imported_at, retained_until)
            VALUES (#{id}, #{kind}, #{status}, #{fileSha256}, #{rowCount}, #{issueCount}, #{duplicateOf},
                    #{checkedBy}, #{checkedByName}, #{at}, #{importedBy}, #{importedByName}, #{importedAt},
                    #{retainedUntil})
            """)
    int insertBatch(@Param("id") UUID id, @Param("kind") String kind, @Param("status") String status,
                    @Param("fileSha256") String fileSha256, @Param("rowCount") int rowCount,
                    @Param("issueCount") int issueCount, @Param("duplicateOf") UUID duplicateOf,
                    @Param("checkedBy") UUID checkedBy, @Param("checkedByName") String checkedByName,
                    @Param("at") OffsetDateTime at, @Param("importedBy") UUID importedBy,
                    @Param("importedByName") String importedByName, @Param("importedAt") OffsetDateTime importedAt,
                    @Param("retainedUntil") OffsetDateTime retainedUntil);

    @Select(BATCH_COLUMNS + " WHERE b.id = #{batchId}")
    BatchRow findBatch(@Param("batchId") UUID batchId);

    @Select(BATCH_COLUMNS + " WHERE b.id = #{batchId} FOR UPDATE")
    BatchRow lockBatch(@Param("batchId") UUID batchId);

    /** 同一类、同样内容真正写入过的那个批次。 */
    @Select("""
            SELECT id
            FROM ops_import_batch
            WHERE kind = #{kind}
              AND file_sha256 = #{fileSha256}
              AND status = 'IMPORTED'
              AND duplicate_of IS NULL
            """)
    UUID findImported(@Param("kind") String kind, @Param("fileSha256") String fileSha256);

    /** 同一类同样内容的导入排队（事务级咨询锁，事务结束自动释放）。 */
    @Select("""
            SELECT 1 FROM (
                SELECT pg_advisory_xact_lock(hashtextextended('ops-import:' || #{kind} || ':' || #{fileSha256}, 0))
            ) locked
            """)
    Integer lockContent(@Param("kind") String kind, @Param("fileSha256") String fileSha256);

    @Update("""
            UPDATE ops_import_batch
            SET status = 'IMPORTED',
                duplicate_of = #{duplicateOf},
                change_count = #{changeCount},
                imported_by = #{importedBy},
                imported_by_name = #{importedByName},
                imported_at = #{at},
                version = version + 1
            WHERE id = #{batchId}
              AND status = 'CHECKED'
            """)
    int markImported(@Param("batchId") UUID batchId, @Param("duplicateOf") UUID duplicateOf,
                     @Param("changeCount") int changeCount, @Param("importedBy") UUID importedBy,
                     @Param("importedByName") String importedByName, @Param("at") OffsetDateTime at);

    @Select("""
            <script>
            SELECT count(*) FROM ops_import_batch
            <if test="kind != null">WHERE kind = #{kind}</if>
            </script>
            """)
    long countBatches(@Param("kind") String kind);

    @Select("""
            <script>
            SELECT b.id, b.kind, b.status, b.file_sha256, b.row_count, b.issue_count, b.change_count, b.duplicate_of,
                   b.checked_by_name, b.checked_at, b.imported_by_name, b.imported_at, b.retained_until
            FROM ops_import_batch b
            <if test="kind != null">WHERE b.kind = #{kind}</if>
            ORDER BY b.checked_at DESC, b.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<BatchRow> findBatches(@Param("kind") String kind, @Param("limit") int limit, @Param("offset") int offset);

    // ---- 文件 ----

    @Insert("""
            INSERT INTO ops_import_file (batch_id, file_name, row_count, size_bytes, sha256, storage_key, sort_order)
            VALUES (#{batchId}, #{fileName}, #{rowCount}, #{sizeBytes}, #{sha256}, #{storageKey}, #{sortOrder})
            """)
    int insertFile(@Param("batchId") UUID batchId, @Param("fileName") String fileName,
                   @Param("rowCount") int rowCount, @Param("sizeBytes") long sizeBytes,
                   @Param("sha256") String sha256, @Param("storageKey") String storageKey,
                   @Param("sortOrder") int sortOrder);

    @Select("""
            SELECT file_name, row_count, storage_key
            FROM ops_import_file
            WHERE batch_id = #{batchId}
            ORDER BY sort_order
            """)
    List<FileRow> findFiles(@Param("batchId") UUID batchId);

    @Select("""
            <script>
            SELECT batch_id, file_name
            FROM ops_import_file
            WHERE batch_id IN
            <foreach collection="batchIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY batch_id, sort_order
            </script>
            """)
    List<FileNameRow> findFileNames(@Param("batchIds") List<UUID> batchIds);

    // ---- 问题 ----

    @Insert("""
            INSERT INTO ops_import_issue (batch_id, sort_order, file_name, line, column_name, message)
            VALUES (#{batchId}, #{sortOrder}, #{fileName}, #{line}, #{column}, #{message})
            """)
    int insertIssue(@Param("batchId") UUID batchId, @Param("sortOrder") int sortOrder,
                    @Param("fileName") String fileName, @Param("line") int line, @Param("column") String column,
                    @Param("message") String message);

    @Select("""
            SELECT file_name, line, column_name, message
            FROM ops_import_issue
            WHERE batch_id = #{batchId}
            ORDER BY sort_order
            """)
    List<IssueRow> findIssues(@Param("batchId") UUID batchId);

    // ---- 改动 ----

    @Insert("""
            INSERT INTO ops_import_change (batch_id, sort_order, ref_kind, reference, target_id, change_type)
            VALUES (#{batchId}, #{sortOrder}, #{refKind}, #{reference}, #{targetId}, #{changeType})
            """)
    int insertChange(@Param("batchId") UUID batchId, @Param("sortOrder") int sortOrder,
                     @Param("refKind") String refKind, @Param("reference") String reference,
                     @Param("targetId") UUID targetId, @Param("changeType") String changeType);

    @Select("""
            SELECT ref_kind, reference, target_id, change_type
            FROM ops_import_change
            WHERE batch_id = #{batchId}
            ORDER BY sort_order
            """)
    List<ChangeRow> findChanges(@Param("batchId") UUID batchId);

    // ---- 编号对照表 ----

    @Select("""
            <script>
            SELECT reference, target_id
            FROM ops_import_ref
            WHERE ref_kind = #{refKind}
              AND reference IN
            <foreach collection="references" item="reference" open="(" separator="," close=")">#{reference}</foreach>
            </script>
            """)
    List<RefRow> findRefs(@Param("refKind") String refKind, @Param("references") List<String> references);

    @Insert("""
            INSERT INTO ops_import_ref (ref_kind, reference, target_id, first_batch_id, last_batch_id, created_at,
                                        updated_at)
            VALUES (#{refKind}, #{reference}, #{targetId}, #{batchId}, #{batchId}, #{at}, #{at})
            ON CONFLICT (ref_kind, reference) DO UPDATE
            SET target_id = EXCLUDED.target_id,
                last_batch_id = EXCLUDED.last_batch_id,
                updated_at = EXCLUDED.updated_at
            """)
    int upsertRef(@Param("refKind") String refKind, @Param("reference") String reference,
                  @Param("targetId") UUID targetId, @Param("batchId") UUID batchId,
                  @Param("at") OffsetDateTime at);

    record BatchRow(UUID id, String kind, String status, String fileSha256, int rowCount, int issueCount,
                    int changeCount, UUID duplicateOf, String checkedByName, OffsetDateTime checkedAt,
                    String importedByName, OffsetDateTime importedAt, OffsetDateTime retainedUntil) {
    }

    record FileRow(String fileName, int rowCount, String storageKey) {
    }

    record FileNameRow(UUID batchId, String fileName) {
    }

    record IssueRow(String fileName, int line, String columnName, String message) {
    }

    record ChangeRow(String refKind, String reference, UUID targetId, String changeType) {
    }

    record RefRow(String reference, UUID targetId) {
    }
}
