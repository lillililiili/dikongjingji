package com.lowaltitude.inspection.course.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.course.application.CourseContract.Operation;
import com.lowaltitude.inspection.course.application.CourseException.Error;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * 契约 contracts/openapi-course.yaml 和实现逐项对照（S5-5）：每个路由都在契约里，公开的三个不要登录、其余要登录；
 * 参数、请求体、返回对象、字段、类型、枚举、上限与 Java 一致；业务错误码与全局异常处理给出的一致。
 */
class CourseOpenApiContractTest {

    private static final Map<String, Class<?>> RECORDS = Map.ofEntries(
            Map.entry("CourseImage", CourseImageView.class),
            Map.entry("CoursePrice", CoursePriceView.class),
            Map.entry("CourseCard", CourseCardView.class),
            Map.entry("CoursePage", CoursePageView.class),
            Map.entry("CourseOrganization", CourseOrganizationView.class),
            Map.entry("CourseInstructor", CourseInstructorView.class),
            Map.entry("CourseLesson", CourseLessonView.class),
            Map.entry("CourseChapter", CourseChapterView.class),
            Map.entry("CourseInclude", CourseIncludeView.class),
            Map.entry("CourseSpec", CourseSpecView.class),
            Map.entry("CourseDetail", CourseDetailView.class),
            Map.entry("CourseMine", CourseMineView.class),
            Map.entry("CourseTrialLesson", CourseTrialLessonView.class),
            Map.entry("CourseTrial", CourseTrialView.class),
            Map.entry("CoursePayment", CoursePaymentView.class),
            Map.entry("CourseBundleSource", CourseBundleSourceView.class),
            Map.entry("CourseRefund", CourseRefundView.class),
            Map.entry("CourseRefundability", CourseRefundabilityView.class),
            Map.entry("CourseProgressLesson", CourseProgressLessonView.class),
            Map.entry("CourseProgressChapter", CourseProgressChapterView.class),
            Map.entry("CourseResume", CourseResumeView.class),
            Map.entry("CourseEnrollment", CourseEnrollmentView.class),
            Map.entry("CourseEnrollmentCard", CourseEnrollmentCardView.class),
            Map.entry("CourseTrialCard", CourseTrialCardView.class),
            Map.entry("CourseEnrollmentPage", CourseEnrollmentPageView.class),
            Map.entry("CoursePlayback", CoursePlaybackView.class));

    private static final Map<String, Class<?>> REQUESTS = Map.of(
            "CourseEnrollRequest", CourseController.EnrollRequest.class);

    private static final Map<String, Class<? extends Enum<?>>> ENUMS = Map.of(
            "CourseMode", CourseMode.class,
            "CourseTopic", CourseTopic.class,
            "CourseSort", CourseSort.class,
            "CoursePriceMode", CoursePriceMode.class,
            "CourseEnrollmentStatus", CourseEnrollmentStatus.class,
            "CourseEnrollmentSource", CourseEnrollmentSource.class,
            "CourseEnrollmentBucket", CourseEnrollmentBucket.class,
            "CourseLessonState", CourseLessonState.class,
            "CourseRefundStatus", CourseRefundStatus.class,
            "CourseRefundBlock", CourseRefundBlock.class);

    private static final Set<String> PUBLIC = Set.of(
            "GET /api/v1/courses", "GET /api/v1/courses/{courseId}", "GET /api/v1/course-trials");

    private final CourseContract contract = new CourseContract("openapi-course.yaml", RECORDS, ENUMS,
            List.of(CourseController.class));

    @Test
    void contractIsVersionOneAndDeclaresBearerJwt() {
        assertThat(CourseContract.map(contract.root().get("info")).get("version")).isEqualTo("1.1.1");
        assertThat(CourseContract.map(CourseContract.map(contract.root().get("components")).get("securitySchemes"))
                .get("bearerAuth")).isEqualTo(Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT"));
        assertThat(contract.root()).doesNotContainKey("security");
    }

    @Test
    void everyRouteIsDeclaredAndOnlyTheCatalogIsPublic() {
        List<Operation> operations = contract.operations();
        assertThat(operations).extracting(Operation::key).containsExactlyInAnyOrder(
                "GET /api/v1/courses",
                "GET /api/v1/courses/{courseId}",
                "GET /api/v1/courses/{courseId}/me",
                "GET /api/v1/course-trials",
                "POST /api/v1/course-enrollments",
                "GET /api/v1/course-enrollments",
                "GET /api/v1/course-enrollments/{enrollmentId}",
                "POST /api/v1/course-enrollments/{enrollmentId}/lessons/{lessonId}/playback",
                "POST /api/v1/course-enrollments/{enrollmentId}/lessons/{lessonId}/completion",
                "POST /api/v1/course-enrollments/{enrollmentId}/refund");
        assertThat(contract.declaredKeys()).containsExactlyInAnyOrderElementsOf(
                operations.stream().map(Operation::key).toList());
        assertThat(operations).allSatisfy(operation -> {
            Map<String, Object> declared = contract.declared(operation);
            Map<String, Object> responses = CourseContract.map(declared.get("responses"));
            assertThat(responses).as(operation.key()).containsKey("400");
            if (PUBLIC.contains(operation.key())) {
                assertThat(declared).as(operation.key()).doesNotContainKey("security");
                assertThat(responses).as(operation.key()).doesNotContainKeys("401", "403");
            } else {
                assertThat(declared.get("security")).as(operation.key())
                        .isEqualTo(List.of(Map.of("bearerAuth", List.of())));
                assertThat(responses).as(operation.key()).containsKeys("401", "403");
            }
            if (operation.path().contains("{")) {
                assertThat(responses).as(operation.key()).containsKey("404");
            }
        });
    }

    @Test
    void parametersBodiesAndResponsesMatchTheControllers() {
        contract.assertParameters();
        contract.assertBodies(REQUESTS);
        Map<String, Object> size = CourseContract.map(CourseContract.map(CourseContract.map(
                contract.root().get("components")).get("parameters")).get("Size"));
        assertThat(CourseContract.map(size.get("schema")).get("maximum")).isEqualTo(CourseService.MAX_PAGE_SIZE);
    }

    @Test
    void everyObjectHasExactlyTheJavaFieldsAndTypes() {
        Map<String, Class<?>> all = new LinkedHashMap<>(RECORDS);
        all.putAll(REQUESTS);
        contract.assertFields(all, Map.of("CourseEnrollRequest", List.of("courseId", "expectedAmountMinor")));
        contract.assertTypes(all);
        contract.assertEnums();
    }

    @Test
    void theRulesInTheDescriptionAreTheOnesTheServiceUses() {
        String text = contract.text();
        assertThat(text).contains("15 分钟").contains("7 天").contains("单人账号").contains("CO 开头");
        assertThat(CourseService.PAY_WINDOW).hasMinutes(15);
        assertThat(CourseService.REFUND_WINDOW).hasDays(7);
        assertThat(CourseService.OPTION_NAME).isEqualTo("单人账号");
        Map<String, Object> keyword = CourseContract.list(CourseContract.map(CourseContract.map(CourseContract.map(
                contract.root().get("paths")).get("/api/v1/courses")).get("get")).get("parameters")).stream()
                .map(CourseContract::map)
                .filter(parameter -> "keyword".equals(parameter.get("name")))
                .findFirst().orElseThrow();
        assertThat(CourseContract.map(keyword.get("schema")).get("maxLength")).isEqualTo(CourseService.MAX_KEYWORD);
    }

    @Test
    void everyBusinessErrorCodeIsDeclaredUnderTheStatusTheHandlerAnswers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Set<String> produced = new TreeSet<>();
        for (Error error : Error.values()) {
            ResponseEntity<ApiError> response = handler.handleCourse(new CourseException(error),
                    new MockHttpServletRequest());
            String code = response.getBody().code();
            String status = String.valueOf(response.getStatusCode().value());
            if (!code.startsWith("COURSE_")) {
                assertThat(code).as(error.name()).isIn("COMMON_NOT_FOUND", "COMMON_FORBIDDEN");
                continue;
            }
            produced.add(code);
            assertThat(status).as(code).isEqualTo("409");
            assertThat(contract.descriptionsUnder(status)).as(code + " under " + status)
                    .anyMatch(description -> description.contains(code));
        }
        assertThat(CourseContract.strings(CourseContract.map(contract.schemas().get("CourseErrorCode")).get("enum")))
                .containsExactlyInAnyOrderElementsOf(produced);
        Set<String> mentioned = new TreeSet<>();
        Matcher matcher = Pattern.compile("COURSE_[A-Z_]+").matcher(contract.text());
        while (matcher.find()) {
            mentioned.add(matcher.group());
        }
        assertThat(mentioned).containsExactlyElementsOf(produced);
        // 不能退款的原因放在 details.reason。
        ResponseEntity<ApiError> refused = handler.handleCourse(new CourseException(Error.REFUND_NOT_ALLOWED,
                Map.of("reason", "STARTED")), new MockHttpServletRequest());
        assertThat(refused.getBody().details()).containsEntry("reason", "STARTED");
    }

    @Test
    void everySchemaInTheContractIsCoveredByThisTest() {
        Set<String> covered = new TreeSet<>(RECORDS.keySet());
        covered.addAll(REQUESTS.keySet());
        covered.addAll(ENUMS.keySet());
        covered.add("CourseErrorCode");
        covered.add("ApiError");
        assertThat(contract.schemas().keySet()).containsExactlyInAnyOrderElementsOf(covered);
    }
}
