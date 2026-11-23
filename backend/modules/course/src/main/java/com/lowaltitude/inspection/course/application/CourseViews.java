package com.lowaltitude.inspection.course.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// 对外的形状，字段与契约 contracts/openapi-course.yaml 逐个对应。

record CourseImageView(String url, String alt) {
}

/** amountMinor 只在 FIXED 时有。 */
record CoursePriceView(CoursePriceMode mode, Long amountMinor) {
}

/**
 * 课程列表的一张卡。线上课：lessonCount 有，scheduleLabel、activityId、cityCode 为空；
 * 线下班：标题、封面、价格、城市取自对应活动，activityId 是那个活动，lessonCount 为空。
 */
record CourseCardView(
        UUID courseId,
        CourseMode mode,
        CourseTopic topic,
        String title,
        CourseImageView coverImage,
        CoursePriceView price,
        Integer lessonCount,
        String scheduleLabel,
        UUID activityId,
        String cityCode
) {
}

record CoursePageView(int page, int size, long total, boolean hasMore, List<CourseCardView> items) {
}

record CourseOrganizationView(UUID organizationId, String name, boolean qualificationReviewed) {
}

record CourseInstructorView(String name, String title) {
}

/** no 是全课第几课（跨章连续）。 */
record CourseLessonView(UUID lessonId, int no, String title, int durationSeconds, boolean trial) {
}

record CourseChapterView(UUID chapterId, String title, List<CourseLessonView> lessons) {
}

record CourseIncludeView(String name, int quantity) {
}

record CourseSpecView(String label, String value) {
}

record CourseDetailView(
        UUID courseId,
        CourseTopic topic,
        String title,
        String subtitle,
        CourseImageView coverImage,
        List<CourseImageView> gallery,
        CoursePriceView price,
        Integer validityDays,
        String ageNote,
        CourseInstructorView instructor,
        CourseOrganizationView organization,
        int lessonCount,
        int totalDurationSeconds,
        int trialLessonCount,
        List<CourseChapterView> chapters,
        List<CourseIncludeView> includes,
        List<CourseSpecView> extraSpecs,
        List<String> intro,
        boolean purchasable,
        boolean onlinePayment,
        UUID experienceItemId
) {
}

record CourseMineView(UUID courseId, UUID enrollmentId, CourseEnrollmentStatus status, UUID tradeOrderId) {
}

record CourseTrialLessonView(UUID lessonId, int no, String chapterTitle, String title, int durationSeconds,
                             String playUrl) {
}

record CourseTrialView(
        UUID courseId,
        String title,
        CourseImageView coverImage,
        CourseOrganizationView organization,
        int lessonCount,
        CoursePriceView price,
        boolean purchasable,
        List<CourseTrialLessonView> lessons
) {
}

record CoursePaymentView(UUID tradeOrderId, long amountMinor, OffsetDateTime payBefore, OffsetDateTime paidAt) {
}

record CourseBundleSourceView(UUID orderId, String orderNo, String productName, String skuName) {
}

record CourseRefundView(UUID refundId, long amountMinor, CourseRefundStatus status, OffsetDateTime requestedAt) {
}

record CourseRefundabilityView(boolean refundable, OffsetDateTime deadline, CourseRefundBlock blockedReason) {
}

record CourseProgressLessonView(UUID lessonId, int no, String title, int durationSeconds, boolean trial,
                                CourseLessonState state) {
}

record CourseProgressChapterView(UUID chapterId, String title, int completedCount,
                                 List<CourseProgressLessonView> lessons) {
}

record CourseResumeView(UUID lessonId, int no, String title) {
}

record CourseEnrollmentView(
        UUID enrollmentId,
        UUID courseId,
        String title,
        CourseImageView coverImage,
        CourseOrganizationView organization,
        CourseEnrollmentSource source,
        CourseEnrollmentStatus status,
        int lessonCount,
        int completedCount,
        CourseResumeView resume,
        OffsetDateTime activatedAt,
        OffsetDateTime expiresAt,
        CoursePaymentView payment,
        CourseBundleSourceView bundle,
        CourseRefundView refund,
        CourseRefundabilityView refundability,
        List<CourseProgressChapterView> chapters,
        int version
) {
}

record CourseEnrollmentCardView(
        UUID enrollmentId,
        UUID courseId,
        String title,
        CourseImageView coverImage,
        CourseEnrollmentSource source,
        CourseEnrollmentStatus status,
        int lessonCount,
        int completedCount,
        CourseResumeView resume,
        CourseBundleSourceView bundle,
        OffsetDateTime expiresAt
) {
}

record CourseTrialCardView(UUID courseId, String title, CourseImageView coverImage, int trialLessonCount) {
}

record CourseEnrollmentPageView(
        int page,
        int size,
        long total,
        boolean hasMore,
        List<CourseEnrollmentCardView> items,
        List<CourseTrialCardView> trials
) {
}

record CoursePlaybackView(UUID enrollmentId, UUID lessonId, String playUrl, int durationSeconds,
                          CourseRefundabilityView refundability) {
}

/** 提交购买的结果：created 为 false 时是重放或已有的待付款记录（200）。 */
record CourseEnrollResult(CourseEnrollmentView enrollment, boolean created) {
}
