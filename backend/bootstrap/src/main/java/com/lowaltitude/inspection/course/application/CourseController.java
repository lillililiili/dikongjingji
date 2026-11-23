package com.lowaltitude.inspection.course.application;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 课程（S5-5，契约 contracts/openapi-course.yaml 1.0.0）。列表、详情、试听公开；其余要登录和客户角色，账号只从令牌里取。
 */
@RestController
public final class CourseController {

    private static final String ENROLLMENT = "/api/v1/course-enrollments/{enrollmentId}";

    private final CourseService service;

    public CourseController(CourseService service) {
        this.service = service;
    }

    // ---- 公开 ----

    @GetMapping("/api/v1/courses")
    public CoursePageView courses(
            @RequestParam String cityCode,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.list(cityCode, topic, keyword, sort, page, size);
    }

    @GetMapping("/api/v1/courses/{courseId}")
    public CourseDetailView course(@PathVariable UUID courseId) {
        return service.detail(courseId);
    }

    @GetMapping("/api/v1/course-trials")
    public CourseTrialView trial(
            @RequestParam(required = false) UUID courseId,
            @RequestParam(required = false) UUID experienceItemId
    ) {
        return service.trial(courseId, experienceItemId);
    }

    // ---- 客户 ----

    @GetMapping("/api/v1/courses/{courseId}/me")
    public CourseMineView mine(Authentication authentication, @PathVariable UUID courseId) {
        return service.mine(CourseSubjects.customer(authentication), courseId);
    }

    /** 买一门课。第一次 201；同一个幂等键重放、已有待付款的 200。 */
    @PostMapping("/api/v1/course-enrollments")
    public ResponseEntity<CourseEnrollmentView> enroll(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody EnrollRequest request
    ) {
        CourseEnrollResult result = service.enroll(CourseSubjects.customer(authentication), idempotencyKey,
                request.courseId(), request.expectedAmountMinor());
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.enrollment());
    }

    @GetMapping("/api/v1/course-enrollments")
    public CourseEnrollmentPageView enrollments(
            Authentication authentication,
            @RequestParam(required = false) String bucket,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.myEnrollments(CourseSubjects.customer(authentication), bucket, page, size);
    }

    @GetMapping(ENROLLMENT)
    public CourseEnrollmentView enrollment(Authentication authentication, @PathVariable UUID enrollmentId) {
        return service.enrollment(CourseSubjects.customer(authentication), enrollmentId);
    }

    @PostMapping(ENROLLMENT + "/lessons/{lessonId}/playback")
    public CoursePlaybackView playback(Authentication authentication, @PathVariable UUID enrollmentId,
                                       @PathVariable UUID lessonId) {
        return service.playback(CourseSubjects.customer(authentication), enrollmentId, lessonId);
    }

    @PostMapping(ENROLLMENT + "/lessons/{lessonId}/completion")
    public CourseEnrollmentView complete(Authentication authentication, @PathVariable UUID enrollmentId,
                                         @PathVariable UUID lessonId) {
        return service.complete(CourseSubjects.customer(authentication), enrollmentId, lessonId);
    }

    @PostMapping(ENROLLMENT + "/refund")
    public CourseEnrollmentView refund(Authentication authentication, @PathVariable UUID enrollmentId) {
        return service.refund(CourseSubjects.customer(authentication), enrollmentId);
    }

    record EnrollRequest(UUID courseId, Long expectedAmountMinor) {
    }
}
