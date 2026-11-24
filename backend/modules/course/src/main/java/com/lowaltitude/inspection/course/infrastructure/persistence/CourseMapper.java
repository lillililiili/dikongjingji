package com.lowaltitude.inspection.course.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 课程目录、开通记录、学习进度的读写（S5-5）。全部用绑定参数；客户的读写以账号为条件。“当前时间”由调用方传入。
 * 加锁顺序：交易订单（交易模块）→ 课程（{@link #lockCourse}）→ 开通记录。
 */
public interface CourseMapper {

    String COURSE_COLUMNS = """
            SELECT c.id,
                   c.organization_id,
                   c.delivery_mode,
                   c.topic,
                   c.title,
                   c.subtitle,
                   c.cover_url,
                   CAST(c.gallery_urls AS text) AS gallery_urls,
                   c.price_amount_minor,
                   c.validity_days,
                   c.age_note,
                   c.instructor_name,
                   c.instructor_title,
                   CAST(c.includes AS text) AS includes,
                   CAST(c.extra_specs AS text) AS extra_specs,
                   CAST(c.intro AS text) AS intro,
                   c.experience_item_id,
                   c.simulation_item_id,
                   c.activity_id,
                   c.schedule_label,
                   c.sort_order,
                   c.status,
                   c.first_listed_at,
                   c.version,
                   c.created_at,
                   c.updated_at
            FROM course c
            """;

    // ---- 目录 ----

    /** 上架的课程（topic 为空时全部），按运营排序。最多 limit 条：列表在内存里再按关键词、城市和价格处理。 */
    @Select("""
            <script>
            """ + COURSE_COLUMNS + """
            WHERE c.status = 'LISTED'
            <if test="topic != null">AND c.topic = #{topic}</if>
            ORDER BY c.sort_order DESC, c.created_at DESC, c.id
            LIMIT #{limit}
            </script>
            """)
    List<CourseRow> findListedCourses(@Param("topic") String topic, @Param("limit") int limit);

    @Select(COURSE_COLUMNS + "WHERE c.id = #{courseId}")
    CourseRow findCourse(@Param("courseId") UUID courseId);

    @Select(COURSE_COLUMNS + "WHERE c.id = #{courseId} FOR UPDATE")
    CourseRow lockCourse(@Param("courseId") UUID courseId);

    @Select(COURSE_COLUMNS + "WHERE c.experience_item_id = #{experienceItemId}")
    CourseRow findCourseByExperience(@Param("experienceItemId") UUID experienceItemId);

    @Select("""
            <script>
            SELECT l.id,
                   l.course_id,
                   l.chapter_id,
                   ch.title AS chapter_title,
                   ch.sort_order AS chapter_sort,
                   l.title,
                   l.duration_seconds,
                   l.trial,
                   l.video_url,
                   l.sort_order
            FROM course_lesson l
            JOIN course_chapter ch ON ch.id = l.chapter_id
            WHERE l.course_id IN
            <foreach collection="courseIds" item="courseId" open="(" separator="," close=")">#{courseId}</foreach>
            ORDER BY l.course_id, l.sort_order
            </script>
            """)
    List<LessonRow> findLessons(@Param("courseIds") Collection<UUID> courseIds);

    /** 附带这些商品的课程（草稿不算）。 */
    @Select("""
            <script>
            SELECT b.course_id, b.product_id
            FROM course_bundle b
            JOIN course c ON c.id = b.course_id
            WHERE c.status IN ('LISTED', 'UNLISTED')
              AND c.delivery_mode = 'ONLINE'
              AND b.product_id IN
            <foreach collection="productIds" item="productId" open="(" separator="," close=")">#{productId}</foreach>
            ORDER BY b.course_id, b.product_id
            </script>
            """)
    List<BundleRow> findBundledCourses(@Param("productIds") Collection<UUID> productIds);

    // ---- 开通记录 ----

    @Select("""
            SELECT id, request_hash
            FROM course_enrollment
            WHERE account_id = #{accountId}
              AND idempotency_key = #{idempotencyKey}
            """)
    KeyRow findByKey(@Param("accountId") UUID accountId, @Param("idempotencyKey") UUID idempotencyKey);

    String ENROLLMENT_COLUMNS = """
            SELECT e.id,
                   e.account_id,
                   e.course_id,
                   e.source,
                   e.status,
                   e.amount_minor,
                   e.trade_order_id,
                   e.pay_before,
                   e.paid_at,
                   e.activated_at,
                   e.expires_at,
                   e.started_at,
                   e.refund_id,
                   e.refund_amount_minor,
                   e.refunded_at,
                   e.bundle_order_id,
                   e.bundle_order_no,
                   e.bundle_product_name,
                   e.bundle_sku_name,
                   e.version,
                   e.created_at
            FROM course_enrollment e
            """;

    /** 这个账号这门课的待付款和开通着的记录（过期的也算开通着，由调用方按时间判断）。 */
    @Select(ENROLLMENT_COLUMNS + """
            WHERE e.account_id = #{accountId}
              AND e.course_id = #{courseId}
              AND e.status IN ('PENDING_PAYMENT', 'ACTIVE')
            ORDER BY e.created_at DESC, e.id
            """)
    List<EnrollmentRow> findLiveEnrollments(@Param("accountId") UUID accountId, @Param("courseId") UUID courseId);

    @Insert("""
            INSERT INTO course_enrollment (
                id, account_id, course_id, source, status, amount_minor, trade_order_id, pay_before,
                idempotency_key, request_hash, created_at, updated_at
            )
            VALUES (
                #{id}, #{accountId}, #{courseId}, 'PURCHASE', 'PENDING_PAYMENT', #{amountMinor}, #{tradeOrderId},
                #{payBefore}, #{idempotencyKey}, #{requestHash}, #{at}, #{at}
            )
            """)
    int insertPurchase(
            @Param("id") UUID id,
            @Param("accountId") UUID accountId,
            @Param("courseId") UUID courseId,
            @Param("amountMinor") long amountMinor,
            @Param("tradeOrderId") UUID tradeOrderId,
            @Param("payBefore") OffsetDateTime payBefore,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("at") OffsetDateTime at
    );

    /** 随商城订单开通；同一张订单同一门课只开一次。 */
    @Insert("""
            INSERT INTO course_enrollment (
                id, account_id, course_id, source, status, activated_at, expires_at,
                bundle_order_id, bundle_order_no, bundle_product_name, bundle_sku_name, created_at, updated_at
            )
            VALUES (
                #{id}, #{accountId}, #{courseId}, 'BUNDLE', 'ACTIVE', #{at}, #{expiresAt},
                #{orderId}, #{orderNo}, #{productName}, #{skuName}, #{at}, #{at}
            )
            ON CONFLICT (bundle_order_id, course_id) DO NOTHING
            """)
    int insertBundle(
            @Param("id") UUID id,
            @Param("accountId") UUID accountId,
            @Param("courseId") UUID courseId,
            @Param("expiresAt") OffsetDateTime expiresAt,
            @Param("orderId") UUID orderId,
            @Param("orderNo") String orderNo,
            @Param("productName") String productName,
            @Param("skuName") String skuName,
            @Param("at") OffsetDateTime at
    );

    @Select(ENROLLMENT_COLUMNS + "WHERE e.account_id = #{accountId} AND e.id = #{enrollmentId}")
    EnrollmentRow findEnrollment(@Param("accountId") UUID accountId, @Param("enrollmentId") UUID enrollmentId);

    @Select(ENROLLMENT_COLUMNS + "WHERE e.account_id = #{accountId} AND e.id = #{enrollmentId} FOR UPDATE")
    EnrollmentRow lockEnrollment(@Param("accountId") UUID accountId, @Param("enrollmentId") UUID enrollmentId);

    /** 交易回调：开通记录和交易订单一起对上才算。 */
    @Select(ENROLLMENT_COLUMNS + "WHERE e.id = #{enrollmentId} AND e.trade_order_id = #{tradeOrderId}")
    EnrollmentRow findPaidEnrollment(@Param("enrollmentId") UUID enrollmentId,
                                     @Param("tradeOrderId") UUID tradeOrderId);

    @Select(ENROLLMENT_COLUMNS + "WHERE e.id = #{enrollmentId} AND e.trade_order_id = #{tradeOrderId} FOR UPDATE")
    EnrollmentRow lockPaidEnrollment(@Param("enrollmentId") UUID enrollmentId,
                                     @Param("tradeOrderId") UUID tradeOrderId);

    /** 付款到账：待付款（或没付款就关闭了的）→ 开通着。 */
    @Update("""
            UPDATE course_enrollment
            SET status = 'ACTIVE',
                paid_at = #{at},
                activated_at = #{at},
                expires_at = #{expiresAt},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{enrollmentId}
              AND status IN ('PENDING_PAYMENT', 'CLOSED')
            """)
    int activate(@Param("enrollmentId") UUID enrollmentId, @Param("expiresAt") OffsetDateTime expiresAt,
                 @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE course_enrollment
            SET status = 'CLOSED', version = version + 1, updated_at = #{at}
            WHERE id = #{enrollmentId}
              AND status = 'PENDING_PAYMENT'
            """)
    int closeUnpaid(@Param("enrollmentId") UUID enrollmentId, @Param("at") OffsetDateTime at);

    /** 过了有效期的旧开通记录在重新开通同一门课之前改成 EXPIRED（唯一索引只许一条 ACTIVE）。 */
    @Update("""
            UPDATE course_enrollment
            SET status = 'EXPIRED', version = version + 1, updated_at = #{now}
            WHERE account_id = #{accountId}
              AND course_id = #{courseId}
              AND status = 'ACTIVE'
              AND expires_at IS NOT NULL
              AND expires_at <= #{now}
            """)
    int expireSuperseded(@Param("accountId") UUID accountId, @Param("courseId") UUID courseId,
                         @Param("now") OffsetDateTime now);

    /** 第一次开始学一节非试听课时：之后不能在线退款。 */
    @Update("""
            UPDATE course_enrollment
            SET started_at = #{at}, version = version + 1, updated_at = #{at}
            WHERE id = #{enrollmentId}
              AND started_at IS NULL
            """)
    int markStarted(@Param("enrollmentId") UUID enrollmentId, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE course_enrollment
            SET status = 'REFUNDED',
                refund_id = #{refundId},
                refund_amount_minor = #{amountMinor},
                refunded_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{enrollmentId}
              AND status = 'ACTIVE'
              AND source = 'PURCHASE'
              AND started_at IS NULL
            """)
    int markRefunded(@Param("enrollmentId") UUID enrollmentId, @Param("refundId") UUID refundId,
                     @Param("amountMinor") long amountMinor, @Param("at") OffsetDateTime at);

    String BUCKET_FILTER = """
            FROM course_enrollment e
            CROSS JOIN LATERAL (
                SELECT count(*) AS total FROM course_lesson l WHERE l.course_id = e.course_id
            ) lessons
            CROSS JOIN LATERAL (
                SELECT count(*) AS done
                FROM course_lesson_progress p
                JOIN course_lesson l ON l.id = p.lesson_id AND l.course_id = e.course_id
                WHERE p.enrollment_id = e.id AND p.completed_at IS NOT NULL
            ) learned
            WHERE e.account_id = #{accountId}
              AND e.status = 'ACTIVE'
              AND (
                  #{bucket} = 'ALL'
                  OR (#{bucket} = 'LEARNING' AND (e.expires_at IS NULL OR e.expires_at > #{now})
                      AND learned.done &lt; lessons.total)
                  OR (#{bucket} = 'FINISHED' AND lessons.total &gt; 0 AND learned.done &gt;= lessons.total)
              )
            """;

    @Select("<script>SELECT count(*) " + BUCKET_FILTER + "</script>")
    long countEnrollments(@Param("accountId") UUID accountId, @Param("bucket") String bucket,
                          @Param("now") OffsetDateTime now);

    @Select("""
            <script>
            SELECT e.id,
                   e.account_id,
                   e.course_id,
                   e.source,
                   e.status,
                   e.amount_minor,
                   e.trade_order_id,
                   e.pay_before,
                   e.paid_at,
                   e.activated_at,
                   e.expires_at,
                   e.started_at,
                   e.refund_id,
                   e.refund_amount_minor,
                   e.refunded_at,
                   e.bundle_order_id,
                   e.bundle_order_no,
                   e.bundle_product_name,
                   e.bundle_sku_name,
                   e.version,
                   e.created_at
            """ + BUCKET_FILTER + """
            ORDER BY e.activated_at DESC, e.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<EnrollmentRow> findEnrollmentPage(
            @Param("accountId") UUID accountId,
            @Param("bucket") String bucket,
            @Param("now") OffsetDateTime now,
            @Param("limit") int limit,
            @Param("offset") long offset
    );

    /** 这个账号开通着的课程（含过期的）。 */
    @Select("""
            SELECT DISTINCT course_id
            FROM course_enrollment
            WHERE account_id = #{accountId}
              AND status = 'ACTIVE'
            """)
    List<UUID> findActiveCourseIds(@Param("accountId") UUID accountId);

    // ---- 学习进度 ----

    @Select("""
            <script>
            SELECT enrollment_id, lesson_id, started_at, completed_at, last_played_at
            FROM course_lesson_progress
            WHERE enrollment_id IN
            <foreach collection="enrollmentIds" item="enrollmentId" open="(" separator="," close=")">#{enrollmentId}</foreach>
            </script>
            """)
    List<ProgressRow> findProgress(@Param("enrollmentIds") Collection<UUID> enrollmentIds);

    @Insert("""
            INSERT INTO course_lesson_progress (enrollment_id, lesson_id, started_at, last_played_at)
            VALUES (#{enrollmentId}, #{lessonId}, #{at}, #{at})
            ON CONFLICT (enrollment_id, lesson_id) DO UPDATE SET last_played_at = EXCLUDED.last_played_at
            """)
    int recordPlayback(@Param("enrollmentId") UUID enrollmentId, @Param("lessonId") UUID lessonId,
                       @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE course_lesson_progress
            SET completed_at = #{at}, last_played_at = #{at}
            WHERE enrollment_id = #{enrollmentId}
              AND lesson_id = #{lessonId}
              AND completed_at IS NULL
            """)
    int markLessonCompleted(@Param("enrollmentId") UUID enrollmentId, @Param("lessonId") UUID lessonId,
                            @Param("at") OffsetDateTime at);

    @Insert("""
            INSERT INTO course_enrollment_event (enrollment_id, event, actor_id, occurred_at)
            VALUES (#{enrollmentId}, #{event}, #{actorId}, #{at})
            """)
    int insertEvent(@Param("enrollmentId") UUID enrollmentId, @Param("event") String event,
                    @Param("actorId") UUID actorId, @Param("at") OffsetDateTime at);

    // ---- 行 ----

    record CourseRow(
            UUID id,
            UUID organizationId,
            String deliveryMode,
            String topic,
            String title,
            String subtitle,
            String coverUrl,
            String galleryUrls,
            Long priceAmountMinor,
            Integer validityDays,
            String ageNote,
            String instructorName,
            String instructorTitle,
            String includes,
            String extraSpecs,
            String intro,
            UUID experienceItemId,
            UUID simulationItemId,
            UUID activityId,
            String scheduleLabel,
            int sortOrder,
            String status,
            OffsetDateTime firstListedAt,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    record LessonRow(
            UUID id,
            UUID courseId,
            UUID chapterId,
            String chapterTitle,
            int chapterSort,
            String title,
            int durationSeconds,
            boolean trial,
            String videoUrl,
            int sortOrder
    ) {
    }

    record BundleRow(UUID courseId, UUID productId) {
    }

    record KeyRow(UUID id, String requestHash) {
    }

    record EnrollmentRow(
            UUID id,
            UUID accountId,
            UUID courseId,
            String source,
            String status,
            long amountMinor,
            UUID tradeOrderId,
            OffsetDateTime payBefore,
            OffsetDateTime paidAt,
            OffsetDateTime activatedAt,
            OffsetDateTime expiresAt,
            OffsetDateTime startedAt,
            UUID refundId,
            Long refundAmountMinor,
            OffsetDateTime refundedAt,
            UUID bundleOrderId,
            String bundleOrderNo,
            String bundleProductName,
            String bundleSkuName,
            int version,
            OffsetDateTime createdAt
    ) {
    }

    record ProgressRow(
            UUID enrollmentId,
            UUID lessonId,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            OffsetDateTime lastPlayedAt
    ) {
    }
}
