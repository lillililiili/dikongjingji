package com.lowaltitude.inspection.course.application.ops;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.course.application.CourseJson;
import com.lowaltitude.inspection.course.application.CoursePorts;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.ChapterDraft;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.CourseDraft;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.CreateCommand;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.LessonDraft;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.OpsCourseChapterView;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.OpsCourseIncludeView;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.OpsCourseLessonView;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.OpsCourseOrganizationView;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.OpsCoursePageView;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.OpsCourseSpecView;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.OpsCourseSummaryView;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.OpsCourseView;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews.Operator;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseMapper;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseMapper.CourseRow;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseMapper.LessonRow;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseOpsMapper;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseOpsMapper.ChapterRow;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseOpsMapper.LearnerRow;
import com.lowaltitude.inspection.course.infrastructure.persistence.CourseOpsMapper.SummaryRow;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import com.lowaltitude.inspection.ops.common.OpsException;
import com.lowaltitude.inspection.ops.common.OpsText;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营端“课程”（S5-5，契约 contracts/openapi-ops-course.yaml 1.1.0）。只给平台运营；写操作带版本号，每次改动记一条改动记录。
 * 规则见契约的描述。
 */
public class CourseOpsService {

    static final int MAX_PAGE_SIZE = 50;
    static final int MAX_TITLE = 60;
    static final int MAX_SUBTITLE = 120;
    static final int MAX_GALLERY = 9;
    static final long MAX_PRICE_MINOR = 10_000_000L;
    static final int MAX_VALIDITY_DAYS = 3650;
    static final int MAX_AGE_NOTE = 30;
    static final int MAX_INSTRUCTOR_NAME = 30;
    static final int MAX_INSTRUCTOR_TITLE = 40;
    static final int MAX_INCLUDES = 6;
    static final int MAX_INCLUDE_NAME = 12;
    static final int MAX_INCLUDE_QUANTITY = 999;
    static final int MAX_SPECS = 6;
    static final int MAX_SPEC_LABEL = 8;
    static final int MAX_SPEC_VALUE = 40;
    static final int MAX_INTRO = 20;
    static final int MAX_INTRO_PARAGRAPH = 500;
    static final int MAX_BUNDLES = 10;
    static final int MAX_SCHEDULE_LABEL = 12;
    static final int MAX_SORT_ORDER = 9999;
    static final int MAX_CHAPTERS = 20;
    static final int MAX_LESSONS_PER_CHAPTER = 30;
    static final int MAX_LESSONS = 200;
    static final int MAX_CHAPTER_TITLE = 40;
    static final int MAX_LESSON_TITLE = 40;
    static final int MAX_DURATION_SECONDS = 14_400;
    static final int MAX_CHANGES = 100;

    private final CourseMapper courses;
    private final CourseOpsMapper mapper;
    private final CoursePorts.Organizations organizations;
    private final CoursePorts.Payments payments;
    private final CoursePorts.Activities activities;
    private final CoursePorts.Simulations simulations;
    private final Clock clock;

    public CourseOpsService(
            CourseMapper courses,
            CourseOpsMapper mapper,
            CoursePorts.Organizations organizations,
            CoursePorts.Payments payments,
            CoursePorts.Activities activities,
            CoursePorts.Simulations simulations,
            Clock clock
    ) {
        this.courses = courses;
        this.mapper = mapper;
        this.organizations = organizations;
        this.payments = payments;
        this.activities = activities;
        this.simulations = simulations;
        this.clock = clock;
    }

    // ---- 读 ----

    @Transactional(readOnly = true)
    public OpsCoursePageView courses(String keywordText, String modeCode, String topicCode, String statusCode,
                                     int page, int size) {
        OpsText.page(page, size, MAX_PAGE_SIZE);
        String keyword = OpsText.keyword(keywordText, 30);
        OpsCourseMode mode = OpsText.optionalEnum(OpsCourseMode.class, modeCode, "mode");
        OpsCourseTopic topic = OpsText.optionalEnum(OpsCourseTopic.class, topicCode, "topic");
        OpsCourseStatus status = OpsText.optionalEnum(OpsCourseStatus.class, statusCode, "status");
        String modeName = mode == null ? null : mode.name();
        String topicName = topic == null ? null : topic.name();
        String statusName = status == null ? null : status.name();
        long total = mapper.countCourses(keyword, modeName, topicName, statusName);
        List<SummaryRow> rows = mapper.findCoursePage(keyword, modeName, topicName, statusName, size,
                (long) (page - 1) * size);
        Map<UUID, String> names = organizations.names(rows.stream().map(SummaryRow::organizationId).collect(Collectors.toSet()));
        List<OpsCourseSummaryView> items = rows.stream().map(row -> new OpsCourseSummaryView(
                row.id(),
                OpsCourseMode.valueOf(row.deliveryMode()),
                OpsCourseTopic.valueOf(row.topic()),
                OpsCourseStatus.valueOf(row.status()),
                row.title(),
                names.getOrDefault(row.organizationId(), ""),
                row.priceAmountMinor(),
                row.lessonCount(),
                row.activityId(),
                row.sortOrder(),
                row.updatedAt())).toList();
        return new OpsCoursePageView(page, size, total, (long) page * size < total, items);
    }

    @Transactional(readOnly = true)
    public OpsCourseView course(UUID courseId) {
        return view(found(courseId));
    }

    @Transactional(readOnly = true)
    public List<OpsChangeView> changes(UUID courseId) {
        found(courseId);
        return mapper.findChanges(courseId, MAX_CHANGES).stream()
                .map(row -> new OpsChangeView(row.id(), "COURSE", row.courseId(), row.action(), row.summary(),
                        row.operatorName(), row.changedAt()))
                .toList();
    }

    // ---- 写 ----

    @Transactional
    public OpsCourseView create(CreateCommand command, Operator operator) {
        if (command == null) {
            throw new IllegalArgumentException("course is required");
        }
        OpsCourseMode mode = OpsText.enumValue(OpsCourseMode.class, command.mode(), "mode");
        OpsCourseTopic topic = OpsText.enumValue(OpsCourseTopic.class, command.topic(), "topic");
        String title = OpsText.required(command.title(), MAX_TITLE, "title");
        requireOrganization(command.organizationId());
        if ((mode == OpsCourseMode.OFFLINE) != (command.activityId() != null)) {
            throw new IllegalArgumentException("activityId is required for an offline course only");
        }
        validateSimulation(mode == OpsCourseMode.ONLINE, command.simulationItemId());
        OffsetDateTime now = now();
        UUID id = UUID.randomUUID();
        mapper.insertCourse(id, command.organizationId(), mode.name(), topic.name(), title, command.activityId(),
                command.simulationItemId(), now);
        record(id, "CREATE", "新建" + (mode == OpsCourseMode.ONLINE ? "线上课" : "线下班") + "草稿“" + title + "”",
                operator, now);
        return view(found(id));
    }

    @Transactional
    public OpsCourseView update(UUID courseId, CourseDraft draft, Integer version, Operator operator) {
        if (draft == null || version == null) {
            throw new IllegalArgumentException("course is required");
        }
        CourseRow row = locked(courseId);
        boolean online = isOnline(row);
        OpsCourseTopic topic = OpsText.enumValue(OpsCourseTopic.class, draft.topic(), "topic");
        String title = OpsText.required(draft.title(), MAX_TITLE, "title");
        String subtitle = OpsText.optional(draft.subtitle(), MAX_SUBTITLE, "subtitle");
        requireOrganization(draft.organizationId());
        String cover = OpsText.mediaUrl(draft.coverUrl(), row.coverUrl(), "coverUrl");
        List<String> gallery = gallery(draft.galleryUrls());
        String ageNote = OpsText.optional(draft.ageNote(), MAX_AGE_NOTE, "ageNote");
        String instructorName = OpsText.optional(draft.instructorName(), MAX_INSTRUCTOR_NAME, "instructorName");
        String instructorTitle = OpsText.optional(draft.instructorTitle(), MAX_INSTRUCTOR_TITLE, "instructorTitle");
        List<CourseJson.Include> includes = includes(draft.includes());
        List<CourseJson.Spec> specs = specs(draft.extraSpecs());
        List<String> intro = intro(draft.intro());
        List<UUID> bundles = draft.bundledProductIds() == null ? List.of() : draft.bundledProductIds();
        if (bundles.size() > MAX_BUNDLES || bundles.stream().anyMatch(Objects::isNull)
                || new HashSet<>(bundles).size() != bundles.size()) {
            throw new IllegalArgumentException("bundledProductIds is invalid");
        }
        int sortOrder = draft.sortOrder() == null ? -1 : draft.sortOrder();
        if (sortOrder < 0 || sortOrder > MAX_SORT_ORDER) {
            throw new IllegalArgumentException("sortOrder is invalid");
        }
        Long price = draft.priceAmountMinor();
        Integer validity = draft.validityDays();
        UUID experience = draft.experienceItemId();
        UUID simulation = row.simulationItemId();
        if (draft.simulationItemSpecified()) {
            simulation = draft.simulationItemId();
            validateSimulation(online, simulation);
        }
        UUID activity = draft.activityId();
        String schedule = OpsText.optional(draft.scheduleLabel(), MAX_SCHEDULE_LABEL, "scheduleLabel");
        if (online) {
            if (price != null && (price < 1 || price > MAX_PRICE_MINOR)) {
                throw new IllegalArgumentException("priceAmountMinor is invalid");
            }
            if (validity != null && (validity < 1 || validity > MAX_VALIDITY_DAYS)) {
                throw new IllegalArgumentException("validityDays is invalid");
            }
            if (activity != null || schedule != null) {
                throw new IllegalArgumentException("an online course has no activity or schedule label");
            }
            if (experience != null && mapper.countExperienceTaken(experience, courseId) > 0) {
                throw new OpsException(CourseOpsError.EXPERIENCE_TAKEN);
            }
        } else {
            if (price != null || validity != null || experience != null || !bundles.isEmpty()
                    || instructorName != null || instructorTitle != null) {
                throw new IllegalArgumentException("an offline course sells nothing itself");
            }
            if (activity == null) {
                throw new IllegalArgumentException("activityId is required for an offline course");
            }
        }
        if (row.version() != version) {
            throw new OpsException(CourseOpsError.VERSION_CONFLICT);
        }
        OffsetDateTime now = now();
        if (mapper.updateCourse(courseId, version, topic.name(), title, subtitle, draft.organizationId(), cover,
                CourseJson.writeStrings(gallery), price, validity, ageNote, instructorName, instructorTitle,
                CourseJson.writeIncludes(includes), CourseJson.writeSpecs(specs), CourseJson.writeStrings(intro),
                experience, simulation, activity, schedule, sortOrder, now) == 0) {
            throw new OpsException(CourseOpsError.VERSION_CONFLICT);
        }
        mapper.deleteBundles(courseId);
        for (UUID productId : bundles) {
            mapper.insertBundle(courseId, productId, now);
        }
        requireCompleteIfListed(courseId);
        record(courseId, "UPDATE", "修改课程资料“" + title + "”", operator, now);
        return view(found(courseId));
    }

    /**
     * 整份保存目录：带 ID 的章、课时改原来的，不带的新建，没出现的删除（有人学过的课时不能删）。课序按提交顺序跨章连续编号。
     */
    @Transactional
    public OpsCourseView outline(UUID courseId, Integer version, List<ChapterDraft> chapters, Operator operator) {
        if (version == null || chapters == null) {
            throw new IllegalArgumentException("outline is required");
        }
        CourseRow row = locked(courseId);
        if (!isOnline(row)) {
            throw new OpsException(CourseOpsError.STATUS_CONFLICT);
        }
        if (chapters.size() > MAX_CHAPTERS) {
            throw new IllegalArgumentException("too many chapters");
        }
        List<LessonRow> existing = courses.findLessons(List.of(courseId));
        Map<UUID, LessonRow> existingLessons = existing.stream().collect(Collectors.toMap(LessonRow::id, lesson -> lesson));
        Set<UUID> existingChapters = mapper.findChapters(courseId).stream().map(ChapterRow::id)
                .collect(Collectors.toSet());
        Set<UUID> keptLessons = new LinkedHashSet<>();
        Set<UUID> keptChapters = new LinkedHashSet<>();
        int lessonTotal = 0;
        List<ChapterDraft> normalized = new ArrayList<>();
        for (ChapterDraft chapter : chapters) {
            if (chapter == null || chapter.lessons() == null || chapter.lessons().size() > MAX_LESSONS_PER_CHAPTER) {
                throw new IllegalArgumentException("chapter is invalid");
            }
            String chapterTitle = OpsText.required(chapter.title(), MAX_CHAPTER_TITLE, "chapter title");
            if (chapter.chapterId() != null && (!existingChapters.contains(chapter.chapterId())
                    || !keptChapters.add(chapter.chapterId()))) {
                throw new IllegalArgumentException("chapterId is not a chapter of this course");
            }
            List<LessonDraft> lessons = new ArrayList<>();
            for (LessonDraft lesson : chapter.lessons()) {
                if (lesson == null || lesson.durationSeconds() == null || lesson.trial() == null
                        || lesson.durationSeconds() < 1 || lesson.durationSeconds() > MAX_DURATION_SECONDS) {
                    throw new IllegalArgumentException("lesson is invalid");
                }
                if (lesson.lessonId() != null && (!existingLessons.containsKey(lesson.lessonId())
                        || !keptLessons.add(lesson.lessonId()))) {
                    throw new IllegalArgumentException("lessonId is not a lesson of this course");
                }
                LessonRow previous = lesson.lessonId() == null ? null : existingLessons.get(lesson.lessonId());
                lessons.add(new LessonDraft(lesson.lessonId(), OpsText.required(lesson.title(), MAX_LESSON_TITLE,
                        "lesson title"), lesson.durationSeconds(), lesson.trial(),
                        OpsText.mediaUrl(lesson.videoUrl(), previous == null ? null : previous.videoUrl(), "videoUrl")));
                lessonTotal++;
            }
            normalized.add(new ChapterDraft(chapter.chapterId(), chapterTitle, lessons));
        }
        if (lessonTotal > MAX_LESSONS) {
            throw new IllegalArgumentException("too many lessons");
        }
        if (row.version() != version) {
            throw new OpsException(CourseOpsError.VERSION_CONFLICT);
        }
        Map<UUID, Integer> learners = learners(courseId);
        for (UUID removed : existingLessons.keySet()) {
            if (!keptLessons.contains(removed) && learners.getOrDefault(removed, 0) > 0) {
                throw new OpsException(CourseOpsError.LESSON_IN_USE);
            }
        }
        OffsetDateTime now = now();
        if (mapper.bumpVersion(courseId, version, now) == 0) {
            throw new OpsException(CourseOpsError.VERSION_CONFLICT);
        }
        mapper.deleteLessonsExcept(courseId, keptLessons);
        mapper.shiftLessons(courseId);
        mapper.shiftChapters(courseId);
        int chapterOrder = 0;
        int lessonOrder = 0;
        Set<UUID> chapterIds = new LinkedHashSet<>();
        for (ChapterDraft chapter : normalized) {
            chapterOrder++;
            UUID chapterId = chapter.chapterId();
            if (chapterId == null) {
                chapterId = UUID.randomUUID();
                mapper.insertChapter(chapterId, courseId, chapter.title(), chapterOrder, now);
            } else {
                mapper.updateChapter(chapterId, courseId, chapter.title(), chapterOrder, now);
            }
            chapterIds.add(chapterId);
            for (LessonDraft lesson : chapter.lessons()) {
                lessonOrder++;
                if (lesson.lessonId() == null) {
                    mapper.insertLesson(UUID.randomUUID(), courseId, chapterId, lesson.title(), lesson.durationSeconds(),
                            lesson.trial(), lesson.videoUrl(), lessonOrder, now);
                } else {
                    mapper.updateLesson(lesson.lessonId(), courseId, chapterId, lesson.title(), lesson.durationSeconds(),
                            lesson.trial(), lesson.videoUrl(), lessonOrder, now);
                }
            }
        }
        mapper.deleteChaptersExcept(courseId, chapterIds);
        requireCompleteIfListed(courseId);
        record(courseId, "OUTLINE", "保存目录：" + normalized.size() + " 章 " + lessonOrder + " 节", operator, now);
        return view(found(courseId));
    }

    @Transactional
    public OpsCourseView list(UUID courseId, Integer version, Operator operator) {
        CourseRow row = locked(courseId);
        requireVersion(row, version);
        if (CourseOpsStatus.LISTED.equals(row.status())) {
            throw new OpsException(CourseOpsError.STATUS_CONFLICT);
        }
        List<OpsCourseListField> missing = missing(row);
        if (!missing.isEmpty()) {
            throw OpsException.incomplete(CourseOpsError.PUBLISH_INCOMPLETE,
                    missing.stream().map(Enum::name).toList());
        }
        // 资质没审核通过的机构不能上架课程（身份迁移 V013）。
        if (organizations.listingSuspended(row.organizationId())) {
            throw new OpsException(CourseOpsError.ORGANIZATION_NOT_QUALIFIED);
        }
        OffsetDateTime now = now();
        if (mapper.setStatus(courseId, version, CourseOpsStatus.LISTED, now) == 0) {
            throw new OpsException(CourseOpsError.VERSION_CONFLICT);
        }
        record(courseId, "LIST", "上架“" + row.title() + "”", operator, now);
        return view(found(courseId));
    }

    @Transactional
    public OpsCourseView unlist(UUID courseId, Integer version, Operator operator) {
        CourseRow row = locked(courseId);
        requireVersion(row, version);
        if (!CourseOpsStatus.LISTED.equals(row.status())) {
            throw new OpsException(CourseOpsError.STATUS_CONFLICT);
        }
        OffsetDateTime now = now();
        if (mapper.setStatus(courseId, version, CourseOpsStatus.UNLISTED, now) == 0) {
            throw new OpsException(CourseOpsError.VERSION_CONFLICT);
        }
        record(courseId, "UNLIST", "下架“" + row.title() + "”", operator, now);
        return view(found(courseId));
    }

    // ---- 校验 ----

    /** 上架前还差的，按页面上的顺序。 */
    List<OpsCourseListField> missing(CourseRow row) {
        List<OpsCourseListField> missing = new ArrayList<>();
        if (row.coverUrl() == null || row.coverUrl().isBlank()) {
            missing.add(OpsCourseListField.coverUrl);
        }
        if (isOnline(row)) {
            List<LessonRow> lessons = courses.findLessons(List.of(row.id()));
            if (lessons.isEmpty()) {
                missing.add(OpsCourseListField.lessons);
            } else if (lessons.stream().anyMatch(lesson -> lesson.videoUrl() == null || lesson.videoUrl().isBlank())) {
                missing.add(OpsCourseListField.videos);
            }
            boolean bundled = !mapper.findBundledProducts(row.id()).isEmpty();
            if (row.priceAmountMinor() == null && !bundled) {
                missing.add(OpsCourseListField.sale);
            }
            if (row.priceAmountMinor() != null && !payments.merchantCanCollect(row.organizationId())) {
                missing.add(OpsCourseListField.merchantAccount);
            }
        } else {
            if (row.scheduleLabel() == null || row.scheduleLabel().isBlank()) {
                missing.add(OpsCourseListField.scheduleLabel);
            }
            if (row.activityId() == null || !activities.exists(row.activityId())) {
                missing.add(OpsCourseListField.activity);
            }
        }
        return List.copyOf(missing);
    }

    private void requireCompleteIfListed(UUID courseId) {
        CourseRow row = found(courseId);
        if (!CourseOpsStatus.LISTED.equals(row.status())) {
            return;
        }
        List<OpsCourseListField> missing = missing(row);
        if (!missing.isEmpty()) {
            throw OpsException.incomplete(CourseOpsError.PUBLISH_INCOMPLETE, missing.stream().map(Enum::name).toList());
        }
    }

    private void requireOrganization(UUID organizationId) {
        if (organizationId == null) {
            throw new IllegalArgumentException("organizationId is required");
        }
        if (!organizations.canHostCourses(organizationId)) {
            throw new OpsException(CourseOpsError.ORGANIZATION_UNKNOWN);
        }
    }

    private static void requireVersion(CourseRow row, Integer version) {
        if (version == null) {
            throw new IllegalArgumentException("version is required");
        }
        if (row.version() != version) {
            throw new OpsException(CourseOpsError.VERSION_CONFLICT);
        }
    }

    private static List<String> gallery(List<String> urls) {
        if (urls == null) {
            return List.of();
        }
        if (urls.size() > MAX_GALLERY) {
            throw new IllegalArgumentException("galleryUrls is too long");
        }
        List<String> out = new ArrayList<>();
        for (String url : urls) {
            String checked = OpsText.httpsUrl(url, "galleryUrls");
            if (checked == null || out.contains(checked)) {
                throw new IllegalArgumentException("galleryUrls is invalid");
            }
            out.add(checked);
        }
        return List.copyOf(out);
    }

    private static List<CourseJson.Include> includes(List<CourseOpsViews.IncludeDraft> drafts) {
        if (drafts == null) {
            return List.of();
        }
        if (drafts.size() > MAX_INCLUDES) {
            throw new IllegalArgumentException("includes is too long");
        }
        return drafts.stream().map(draft -> {
            if (draft == null || draft.quantity() == null || draft.quantity() < 1
                    || draft.quantity() > MAX_INCLUDE_QUANTITY) {
                throw new IllegalArgumentException("include is invalid");
            }
            return new CourseJson.Include(OpsText.required(draft.name(), MAX_INCLUDE_NAME, "include name"),
                    draft.quantity());
        }).toList();
    }

    private static List<CourseJson.Spec> specs(List<CourseOpsViews.SpecDraft> drafts) {
        if (drafts == null) {
            return List.of();
        }
        if (drafts.size() > MAX_SPECS) {
            throw new IllegalArgumentException("extraSpecs is too long");
        }
        return drafts.stream().map(draft -> {
            if (draft == null) {
                throw new IllegalArgumentException("spec is invalid");
            }
            return new CourseJson.Spec(OpsText.required(draft.label(), MAX_SPEC_LABEL, "spec label"),
                    OpsText.required(draft.value(), MAX_SPEC_VALUE, "spec value"));
        }).toList();
    }

    private static List<String> intro(List<String> paragraphs) {
        if (paragraphs == null) {
            return List.of();
        }
        if (paragraphs.size() > MAX_INTRO) {
            throw new IllegalArgumentException("intro is too long");
        }
        return paragraphs.stream().map(text -> {
            String paragraph = OpsText.multiline(text, MAX_INTRO_PARAGRAPH, "intro");
            if (paragraph == null) {
                throw new IllegalArgumentException("intro paragraph is empty");
            }
            return paragraph;
        }).toList();
    }

    // ---- 拼视图 ----

    private OpsCourseView view(CourseRow row) {
        List<LessonRow> lessons = courses.findLessons(List.of(row.id()));
        Map<UUID, Integer> learners = learners(row.id());
        Map<UUID, List<OpsCourseLessonView>> grouped = new HashMap<>();
        for (int index = 0; index < lessons.size(); index++) {
            LessonRow lesson = lessons.get(index);
            grouped.computeIfAbsent(lesson.chapterId(), ignored -> new ArrayList<>()).add(new OpsCourseLessonView(
                    lesson.id(), index + 1, lesson.title(), lesson.durationSeconds(), lesson.trial(), lesson.videoUrl(),
                    learners.getOrDefault(lesson.id(), 0)));
        }
        // 按章的序号排；还没有课时的章（刚建的空章）也给出来。
        List<OpsCourseChapterView> chapters = mapper.findChapters(row.id()).stream()
                .map(chapter -> new OpsCourseChapterView(chapter.id(), chapter.title(),
                        List.copyOf(grouped.getOrDefault(chapter.id(), List.of()))))
                .toList();
        String organizationName = organizations.names(List.of(row.organizationId())).getOrDefault(row.organizationId(), "");
        return new OpsCourseView(
                row.id(),
                OpsCourseMode.valueOf(row.deliveryMode()),
                OpsCourseTopic.valueOf(row.topic()),
                OpsCourseStatus.valueOf(row.status()),
                row.title(),
                row.subtitle(),
                new OpsCourseOrganizationView(row.organizationId(), organizationName),
                row.coverUrl(),
                CourseJson.strings(row.galleryUrls()),
                row.priceAmountMinor(),
                row.validityDays(),
                row.ageNote(),
                row.instructorName(),
                row.instructorTitle(),
                CourseJson.includes(row.includes()).stream()
                        .map(item -> new OpsCourseIncludeView(item.name(), item.quantity())).toList(),
                CourseJson.specs(row.extraSpecs()).stream()
                        .map(item -> new OpsCourseSpecView(item.label(), item.value())).toList(),
                CourseJson.strings(row.intro()),
                row.experienceItemId(),
                row.simulationItemId(),
                mapper.findBundledProducts(row.id()),
                row.activityId(),
                row.scheduleLabel(),
                row.sortOrder(),
                chapters,
                lessons.size(),
                mapper.countEnrollments(row.id()),
                missing(row),
                row.firstListedAt(),
                row.createdAt(),
                row.updatedAt(),
                row.version());
    }

    private Map<UUID, Integer> learners(UUID courseId) {
        return mapper.countLearners(courseId).stream()
                .collect(Collectors.toMap(LearnerRow::lessonId, LearnerRow::learners, Integer::sum));
    }

    private void record(UUID courseId, String action, String summary, Operator operator, OffsetDateTime at) {
        if (operator == null || operator.accountId() == null) {
            throw new IllegalArgumentException("operator is required");
        }
        mapper.insertChange(courseId, action, OpsText.clip(summary, 200), operator.accountId(),
                OpsText.operatorName(operator.displayName()), at);
    }

    private CourseRow found(UUID courseId) {
        if (courseId == null) {
            throw new IllegalArgumentException("course id is required");
        }
        CourseRow row = courses.findCourse(courseId);
        if (row == null) {
            throw new OpsException(CourseOpsError.NOT_FOUND);
        }
        return row;
    }

    private CourseRow locked(UUID courseId) {
        if (courseId == null) {
            throw new IllegalArgumentException("course id is required");
        }
        CourseRow row = courses.lockCourse(courseId);
        if (row == null) {
            throw new OpsException(CourseOpsError.NOT_FOUND);
        }
        return row;
    }

    private void validateSimulation(boolean online, UUID itemId) {
        if (itemId != null && (!online || !simulations.isPlayable(itemId))) {
            throw new IllegalArgumentException("simulationItemId requires an online course and a published playable simulation");
        }
    }

    private static boolean isOnline(CourseRow row) {
        return OpsCourseMode.ONLINE.name().equals(row.deliveryMode());
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    /** course.status 的取值。 */
    private static final class CourseOpsStatus {
        static final String LISTED = "LISTED";
        static final String UNLISTED = "UNLISTED";
    }
}
