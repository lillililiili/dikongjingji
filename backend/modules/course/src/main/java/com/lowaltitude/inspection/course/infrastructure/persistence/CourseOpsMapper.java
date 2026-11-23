package com.lowaltitude.inspection.course.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 运营端课程的读写（S5-5，契约 contracts/openapi-ops-course.yaml）。全部用绑定参数；改课程都带版本号（条件更新）。
 * 读课程本身、课时用 {@link CourseMapper}。
 */
public interface CourseOpsMapper {

    String SUMMARY_FILTER = """
            FROM course c
            WHERE (CAST(#{keyword} AS text) IS NULL OR c.title ILIKE '%' || CAST(#{keyword} AS text) || '%')
              AND (CAST(#{mode} AS text) IS NULL OR c.delivery_mode = CAST(#{mode} AS text))
              AND (CAST(#{topic} AS text) IS NULL OR c.topic = CAST(#{topic} AS text))
              AND (CAST(#{status} AS text) IS NULL OR c.status = CAST(#{status} AS text))
            """;

    @Select("SELECT count(*) " + SUMMARY_FILTER)
    long countCourses(@Param("keyword") String keyword, @Param("mode") String mode, @Param("topic") String topic,
                      @Param("status") String status);

    @Select("""
            SELECT c.id,
                   c.delivery_mode,
                   c.topic,
                   c.status,
                   c.title,
                   c.organization_id,
                   c.price_amount_minor,
                   CAST((SELECT count(*) FROM course_lesson l WHERE l.course_id = c.id) AS integer) AS lesson_count,
                   c.activity_id,
                   c.sort_order,
                   c.updated_at
            """ + SUMMARY_FILTER + """
            ORDER BY c.updated_at DESC, c.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<SummaryRow> findCoursePage(
            @Param("keyword") String keyword,
            @Param("mode") String mode,
            @Param("topic") String topic,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") long offset
    );

    @Insert("""
            INSERT INTO course (id, organization_id, delivery_mode, topic, title, activity_id, created_at, updated_at)
            VALUES (#{id}, #{organizationId}, #{mode}, #{topic}, #{title}, #{activityId}, #{at}, #{at})
            """)
    int insertCourse(
            @Param("id") UUID id,
            @Param("organizationId") UUID organizationId,
            @Param("mode") String mode,
            @Param("topic") String topic,
            @Param("title") String title,
            @Param("activityId") UUID activityId,
            @Param("at") OffsetDateTime at
    );

    @Update("""
            UPDATE course
            SET topic = #{topic},
                title = #{title},
                subtitle = #{subtitle},
                organization_id = #{organizationId},
                cover_url = #{coverUrl},
                gallery_urls = CAST(#{galleryUrls} AS jsonb),
                price_amount_minor = #{priceAmountMinor},
                validity_days = #{validityDays},
                age_note = #{ageNote},
                instructor_name = #{instructorName},
                instructor_title = #{instructorTitle},
                includes = CAST(#{includes} AS jsonb),
                extra_specs = CAST(#{extraSpecs} AS jsonb),
                intro = CAST(#{intro} AS jsonb),
                experience_item_id = #{experienceItemId},
                activity_id = #{activityId},
                schedule_label = #{scheduleLabel},
                sort_order = #{sortOrder},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{id}
              AND version = #{version}
            """)
    int updateCourse(
            @Param("id") UUID id,
            @Param("version") int version,
            @Param("topic") String topic,
            @Param("title") String title,
            @Param("subtitle") String subtitle,
            @Param("organizationId") UUID organizationId,
            @Param("coverUrl") String coverUrl,
            @Param("galleryUrls") String galleryUrls,
            @Param("priceAmountMinor") Long priceAmountMinor,
            @Param("validityDays") Integer validityDays,
            @Param("ageNote") String ageNote,
            @Param("instructorName") String instructorName,
            @Param("instructorTitle") String instructorTitle,
            @Param("includes") String includes,
            @Param("extraSpecs") String extraSpecs,
            @Param("intro") String intro,
            @Param("experienceItemId") UUID experienceItemId,
            @Param("activityId") UUID activityId,
            @Param("scheduleLabel") String scheduleLabel,
            @Param("sortOrder") int sortOrder,
            @Param("at") OffsetDateTime at
    );

    /** 上架、下架：版本对得上才改；第一次上架记时间。 */
    @Update("""
            UPDATE course
            SET status = #{status},
                first_listed_at = CASE WHEN #{status} = 'LISTED' THEN coalesce(first_listed_at, #{at}) ELSE first_listed_at END,
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{id}
              AND version = #{version}
            """)
    int setStatus(@Param("id") UUID id, @Param("version") int version, @Param("status") String status,
                  @Param("at") OffsetDateTime at);

    /** 改目录时只动版本号。 */
    @Update("""
            UPDATE course
            SET version = version + 1, updated_at = #{at}
            WHERE id = #{id}
              AND version = #{version}
            """)
    int bumpVersion(@Param("id") UUID id, @Param("version") int version, @Param("at") OffsetDateTime at);

    @Select("SELECT count(*) FROM course WHERE experience_item_id = #{experienceItemId} AND id <> #{courseId}")
    long countExperienceTaken(@Param("experienceItemId") UUID experienceItemId, @Param("courseId") UUID courseId);

    @Select("SELECT count(*) FROM course_enrollment WHERE course_id = #{courseId} AND status IN ('ACTIVE', 'REFUNDED', 'EXPIRED')")
    long countEnrollments(@Param("courseId") UUID courseId);

    // ---- 附带商品 ----

    @Select("SELECT product_id FROM course_bundle WHERE course_id = #{courseId} ORDER BY created_at, product_id")
    List<UUID> findBundledProducts(@Param("courseId") UUID courseId);

    @Delete("DELETE FROM course_bundle WHERE course_id = #{courseId}")
    int deleteBundles(@Param("courseId") UUID courseId);

    @Insert("""
            INSERT INTO course_bundle (course_id, product_id, created_at)
            VALUES (#{courseId}, #{productId}, #{at})
            """)
    int insertBundle(@Param("courseId") UUID courseId, @Param("productId") UUID productId,
                     @Param("at") OffsetDateTime at);

    // ---- 目录 ----

    @Select("""
            SELECT p.lesson_id, CAST(count(*) AS integer) AS learners
            FROM course_lesson_progress p
            JOIN course_lesson l ON l.id = p.lesson_id
            WHERE l.course_id = #{courseId}
            GROUP BY p.lesson_id
            """)
    List<LearnerRow> countLearners(@Param("courseId") UUID courseId);

    /** 重新编号前把这门课的章、课时的序号挪开，避免和新的序号撞上唯一约束。 */
    @Update("UPDATE course_chapter SET sort_order = sort_order + 100000 WHERE course_id = #{courseId}")
    int shiftChapters(@Param("courseId") UUID courseId);

    @Update("UPDATE course_lesson SET sort_order = sort_order + 100000 WHERE course_id = #{courseId}")
    int shiftLessons(@Param("courseId") UUID courseId);

    @Delete("""
            <script>
            DELETE FROM course_lesson
            WHERE course_id = #{courseId}
            <if test="keep != null and !keep.isEmpty()">
              AND id NOT IN
              <foreach collection="keep" item="id" open="(" separator="," close=")">#{id}</foreach>
            </if>
            </script>
            """)
    int deleteLessonsExcept(@Param("courseId") UUID courseId, @Param("keep") Collection<UUID> keep);

    @Delete("""
            <script>
            DELETE FROM course_chapter
            WHERE course_id = #{courseId}
            <if test="keep != null and !keep.isEmpty()">
              AND id NOT IN
              <foreach collection="keep" item="id" open="(" separator="," close=")">#{id}</foreach>
            </if>
            </script>
            """)
    int deleteChaptersExcept(@Param("courseId") UUID courseId, @Param("keep") Collection<UUID> keep);

    @Select("SELECT id, title, sort_order FROM course_chapter WHERE course_id = #{courseId} ORDER BY sort_order, id")
    List<ChapterRow> findChapters(@Param("courseId") UUID courseId);

    @Insert("""
            INSERT INTO course_chapter (id, course_id, title, sort_order, created_at, updated_at)
            VALUES (#{id}, #{courseId}, #{title}, #{sortOrder}, #{at}, #{at})
            """)
    int insertChapter(@Param("id") UUID id, @Param("courseId") UUID courseId, @Param("title") String title,
                      @Param("sortOrder") int sortOrder, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE course_chapter
            SET title = #{title}, sort_order = #{sortOrder}, updated_at = #{at}
            WHERE id = #{id} AND course_id = #{courseId}
            """)
    int updateChapter(@Param("id") UUID id, @Param("courseId") UUID courseId, @Param("title") String title,
                      @Param("sortOrder") int sortOrder, @Param("at") OffsetDateTime at);

    @Insert("""
            INSERT INTO course_lesson (
                id, course_id, chapter_id, title, duration_seconds, trial, video_url, sort_order, created_at, updated_at
            )
            VALUES (
                #{id}, #{courseId}, #{chapterId}, #{title}, #{durationSeconds}, #{trial}, #{videoUrl}, #{sortOrder},
                #{at}, #{at}
            )
            """)
    int insertLesson(
            @Param("id") UUID id,
            @Param("courseId") UUID courseId,
            @Param("chapterId") UUID chapterId,
            @Param("title") String title,
            @Param("durationSeconds") int durationSeconds,
            @Param("trial") boolean trial,
            @Param("videoUrl") String videoUrl,
            @Param("sortOrder") int sortOrder,
            @Param("at") OffsetDateTime at
    );

    @Update("""
            UPDATE course_lesson
            SET chapter_id = #{chapterId},
                title = #{title},
                duration_seconds = #{durationSeconds},
                trial = #{trial},
                video_url = #{videoUrl},
                sort_order = #{sortOrder},
                updated_at = #{at}
            WHERE id = #{id} AND course_id = #{courseId}
            """)
    int updateLesson(
            @Param("id") UUID id,
            @Param("courseId") UUID courseId,
            @Param("chapterId") UUID chapterId,
            @Param("title") String title,
            @Param("durationSeconds") int durationSeconds,
            @Param("trial") boolean trial,
            @Param("videoUrl") String videoUrl,
            @Param("sortOrder") int sortOrder,
            @Param("at") OffsetDateTime at
    );

    // ---- 改动记录 ----

    @Insert("""
            INSERT INTO course_ops_change (course_id, action, summary, operator_id, operator_name, changed_at)
            VALUES (#{courseId}, #{action}, #{summary}, #{operatorId}, #{operatorName}, #{at})
            """)
    int insertChange(
            @Param("courseId") UUID courseId,
            @Param("action") String action,
            @Param("summary") String summary,
            @Param("operatorId") UUID operatorId,
            @Param("operatorName") String operatorName,
            @Param("at") OffsetDateTime at
    );

    @Select("""
            SELECT id, course_id, action, summary, operator_name, changed_at
            FROM course_ops_change
            WHERE course_id = #{courseId}
            ORDER BY changed_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<ChangeRow> findChanges(@Param("courseId") UUID courseId, @Param("limit") int limit);

    record SummaryRow(
            UUID id,
            String deliveryMode,
            String topic,
            String status,
            String title,
            UUID organizationId,
            Long priceAmountMinor,
            int lessonCount,
            UUID activityId,
            int sortOrder,
            OffsetDateTime updatedAt
    ) {
    }

    record LearnerRow(UUID lessonId, int learners) {
    }

    record ChapterRow(UUID id, String title, int sortOrder) {
    }

    record ChangeRow(UUID id, UUID courseId, String action, String summary, String operatorName,
                     OffsetDateTime changedAt) {
    }
}
