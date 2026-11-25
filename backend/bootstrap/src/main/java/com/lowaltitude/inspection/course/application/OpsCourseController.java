package com.lowaltitude.inspection.course.application;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.lowaltitude.inspection.course.application.ops.CourseOpsService;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.ChapterDraft;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.CourseDraft;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.CreateCommand;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.IncludeDraft;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.LessonDraft;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.OpsCoursePageView;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.OpsCourseView;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.Operator;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.SpecDraft;
import com.lowaltitude.inspection.ops.OpsActor;
import com.lowaltitude.inspection.ops.OpsActors;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端“课程”（S5-5，契约 contracts/openapi-ops-course.yaml 1.1.0）。只给平台运营；写操作带版本号，
 * 每次改动记一条改动记录（操作人显示名取自身份资料）。
 */
@RestController
public final class OpsCourseController {

    private static final String COURSES = "/api/v1/ops/courses";
    private static final String COURSE = COURSES + "/{courseId}";

    private final CourseOpsService service;
    private final OpsActors actors;

    public OpsCourseController(CourseOpsService service, OpsActors actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping(COURSES)
    public OpsCoursePageView courses(
            Authentication authentication,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) String topic,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        actors.operator(authentication);
        return service.courses(keyword, mode, topic, status, page, size);
    }

    @PostMapping(COURSES)
    public ResponseEntity<OpsCourseView> create(Authentication authentication, @RequestBody CreateRequest request) {
        Operator operator = operator(authentication);
        return ResponseEntity.status(201).body(service.create(new CreateCommand(request.mode(), request.topic(),
                request.title(), request.organizationId(), request.activityId(), request.simulationItemId()), operator));
    }

    @GetMapping(COURSE)
    public OpsCourseView course(Authentication authentication, @PathVariable UUID courseId) {
        actors.operator(authentication);
        return service.course(courseId);
    }

    @PutMapping(COURSE)
    public OpsCourseView update(Authentication authentication, @PathVariable UUID courseId,
                                @RequestBody CourseRequest request) {
        Operator operator = operator(authentication);
        return service.update(courseId, request.draft(), request.version(), operator);
    }

    @PutMapping(COURSE + "/outline")
    public OpsCourseView outline(Authentication authentication, @PathVariable UUID courseId,
                                 @RequestBody OutlineRequest request) {
        Operator operator = operator(authentication);
        List<ChapterDraft> chapters = request.chapters() == null ? null : request.chapters().stream()
                .map(chapter -> chapter == null ? null : new ChapterDraft(chapter.chapterId(), chapter.title(),
                        chapter.lessons() == null ? null : chapter.lessons().stream()
                                .map(lesson -> lesson == null ? null : new LessonDraft(lesson.lessonId(), lesson.title(),
                                        lesson.durationSeconds(), lesson.trial(), lesson.videoUrl()))
                                .toList()))
                .toList();
        return service.outline(courseId, request.version(), chapters, operator);
    }

    @PostMapping(COURSE + "/list")
    public OpsCourseView list(Authentication authentication, @PathVariable UUID courseId,
                              @RequestBody VersionRequest request) {
        return service.list(courseId, request.version(), operator(authentication));
    }

    @PostMapping(COURSE + "/unlist")
    public OpsCourseView unlist(Authentication authentication, @PathVariable UUID courseId,
                                @RequestBody VersionRequest request) {
        return service.unlist(courseId, request.version(), operator(authentication));
    }

    @GetMapping(COURSE + "/changes")
    public List<OpsChangeView> changes(Authentication authentication, @PathVariable UUID courseId) {
        actors.operator(authentication);
        return service.changes(courseId);
    }

    private Operator operator(Authentication authentication) {
        OpsActor actor = actors.operator(authentication);
        return new Operator(actor.accountId(), actor.displayName());
    }

    record CreateRequest(String mode, String topic, String title, UUID organizationId, UUID activityId,
                         UUID simulationItemId) {
    }

    record CourseRequest(
            Integer version,
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
            List<IncludeRequest> includes,
            List<SpecRequest> extraSpecs,
            List<String> intro,
            UUID experienceItemId,
            JsonNode simulationItemId,
            List<UUID> bundledProductIds,
            UUID activityId,
            String scheduleLabel,
            Integer sortOrder
    ) {
        CourseDraft draft() {
            return new CourseDraft(topic, title, subtitle, organizationId, coverUrl, galleryUrls, priceAmountMinor,
                    validityDays, ageNote, instructorName, instructorTitle,
                    includes == null ? null : includes.stream()
                            .map(item -> item == null ? null : new IncludeDraft(item.name(), item.quantity())).toList(),
                    extraSpecs == null ? null : extraSpecs.stream()
                            .map(item -> item == null ? null : new SpecDraft(item.label(), item.value())).toList(),
                    intro, experienceItemId, simulationId(), simulationItemId != null, bundledProductIds, activityId,
                    scheduleLabel, sortOrder);
        }

        // Jackson supplies Java null for an absent tree property and a NullNode for an explicit JSON null.
        private UUID simulationId() {
            if (simulationItemId == null || simulationItemId.isNull()) {
                return null;
            }
            String value = simulationItemId.textValue();
            if (value == null || value.length() != 36) {
                throw new IllegalArgumentException("simulationItemId must be a UUID or null");
            }
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("simulationItemId must be a UUID or null");
            }
            return id;
        }
    }

    record IncludeRequest(String name, Integer quantity) {
    }

    record SpecRequest(String label, String value) {
    }

    record OutlineRequest(Integer version, List<ChapterRequest> chapters) {
    }

    record ChapterRequest(UUID chapterId, String title, List<LessonRequest> lessons) {
    }

    record LessonRequest(UUID lessonId, String title, Integer durationSeconds, Boolean trial, String videoUrl) {
    }

    record VersionRequest(Integer version) {
    }
}
