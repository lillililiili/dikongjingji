package com.lowaltitude.inspection.course.application.ops;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 运营端课程的对外形状和命令（字段与契约 contracts/openapi-ops-course.yaml 逐个对应）。 */
public final class CourseOpsViews {

    private CourseOpsViews() {
    }

    public record OpsCourseOrganizationView(UUID organizationId, String name) {
    }

    public record OpsCourseIncludeView(String name, int quantity) {
    }

    public record OpsCourseSpecView(String label, String value) {
    }

    public record OpsCourseLessonView(
            UUID lessonId,
            int no,
            String title,
            int durationSeconds,
            boolean trial,
            String videoUrl,
            int learnerCount
    ) {
    }

    public record OpsCourseChapterView(UUID chapterId, String title, List<OpsCourseLessonView> lessons) {
    }

    public record OpsCourseView(
            UUID courseId,
            OpsCourseMode mode,
            OpsCourseTopic topic,
            OpsCourseStatus status,
            String title,
            String subtitle,
            OpsCourseOrganizationView organization,
            String coverUrl,
            List<String> galleryUrls,
            Long priceAmountMinor,
            Integer validityDays,
            String ageNote,
            String instructorName,
            String instructorTitle,
            List<OpsCourseIncludeView> includes,
            List<OpsCourseSpecView> extraSpecs,
            List<String> intro,
            UUID experienceItemId,
            List<UUID> bundledProductIds,
            UUID activityId,
            String scheduleLabel,
            int sortOrder,
            List<OpsCourseChapterView> chapters,
            int lessonCount,
            long enrollmentCount,
            List<OpsCourseListField> listMissing,
            OffsetDateTime firstListedAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version
    ) {
    }

    public record OpsCourseSummaryView(
            UUID courseId,
            OpsCourseMode mode,
            OpsCourseTopic topic,
            OpsCourseStatus status,
            String title,
            String organizationName,
            Long priceAmountMinor,
            int lessonCount,
            UUID activityId,
            int sortOrder,
            OffsetDateTime updatedAt
    ) {
    }

    public record OpsCoursePageView(int page, int size, long total, boolean hasMore, List<OpsCourseSummaryView> items) {
    }

    // ---- 命令 ----

    public record Operator(UUID accountId, String displayName) {
    }

    public record CreateCommand(String mode, String topic, String title, UUID organizationId, UUID activityId) {
    }

    public record IncludeDraft(String name, Integer quantity) {
    }

    public record SpecDraft(String label, String value) {
    }

    public record CourseDraft(
            String topic,
            String title,
            String subtitle,
            UUID organizationId,
            String coverUrl,
            List<String> galleryUrls,
            Long priceAmountMinor,
            Integer validityDays,
            String ageNote,
            String instructorName,
            String instructorTitle,
            List<IncludeDraft> includes,
            List<SpecDraft> extraSpecs,
            List<String> intro,
            UUID experienceItemId,
            List<UUID> bundledProductIds,
            UUID activityId,
            String scheduleLabel,
            Integer sortOrder
    ) {
    }

    public record LessonDraft(UUID lessonId, String title, Integer durationSeconds, Boolean trial, String videoUrl) {
    }

    public record ChapterDraft(UUID chapterId, String title, List<LessonDraft> lessons) {
    }
}
