package com.lowaltitude.inspection.course.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.course.application.CourseContract.Operation;
import com.lowaltitude.inspection.course.application.ops.CourseOpsError;
import com.lowaltitude.inspection.course.application.ops.CourseOpsViews;
import com.lowaltitude.inspection.course.application.ops.OpsCourseListField;
import com.lowaltitude.inspection.course.application.ops.OpsCourseMode;
import com.lowaltitude.inspection.course.application.ops.OpsCourseStatus;
import com.lowaltitude.inspection.course.application.ops.OpsCourseTopic;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import org.junit.jupiter.api.Test;

/**
 * 契约 contracts/openapi-ops-course.yaml 和运营端课程控制器逐项对照（S5-5）：每个路由都要登录，路径、参数、请求体、
 * 返回对象和字段类型与 Java 一致，错误码就是 CourseOpsError 的全部值。
 */
class OpsCourseOpenApiContractTest {

    private static final Map<String, Class<?>> RECORDS = Map.ofEntries(
            Map.entry("OpsCourseOrganization", CourseOpsViews.OpsCourseOrganizationView.class),
            Map.entry("OpsCourseInclude", CourseOpsViews.OpsCourseIncludeView.class),
            Map.entry("OpsCourseSpec", CourseOpsViews.OpsCourseSpecView.class),
            Map.entry("OpsCourseLesson", CourseOpsViews.OpsCourseLessonView.class),
            Map.entry("OpsCourseChapter", CourseOpsViews.OpsCourseChapterView.class),
            Map.entry("OpsCourse", CourseOpsViews.OpsCourseView.class),
            Map.entry("OpsCourseSummary", CourseOpsViews.OpsCourseSummaryView.class),
            Map.entry("OpsCoursePage", CourseOpsViews.OpsCoursePageView.class),
            Map.entry("OpsChange", OpsChangeView.class));

    private static final Map<String, Class<?>> REQUESTS = Map.of(
            "OpsCourseCreateRequest", OpsCourseController.CreateRequest.class,
            "OpsCourseRequest", OpsCourseController.CourseRequest.class,
            "OpsCourseIncludeRequest", OpsCourseController.IncludeRequest.class,
            "OpsCourseSpecRequest", OpsCourseController.SpecRequest.class,
            "OpsCourseOutlineRequest", OpsCourseController.OutlineRequest.class,
            "OpsCourseChapterRequest", OpsCourseController.ChapterRequest.class,
            "OpsCourseLessonRequest", OpsCourseController.LessonRequest.class,
            "OpsCourseVersionRequest", OpsCourseController.VersionRequest.class);

    private static final Map<String, Class<? extends Enum<?>>> ENUMS = Map.of(
            "OpsCourseMode", OpsCourseMode.class,
            "OpsCourseTopic", OpsCourseTopic.class,
            "OpsCourseStatus", OpsCourseStatus.class,
            "OpsCourseListField", OpsCourseListField.class);

    private final CourseContract contract = new CourseContract("openapi-ops-course.yaml", RECORDS, ENUMS,
            List.of(OpsCourseController.class));

    @Test
    void everyRouteIsDeclaredAndNeedsALogin() {
        assertThat(CourseContract.map(contract.root().get("info")).get("version")).isEqualTo("1.0.0");
        List<Operation> operations = contract.operations();
        assertThat(operations).extracting(Operation::key).containsExactlyInAnyOrder(
                "GET /api/v1/ops/courses",
                "POST /api/v1/ops/courses",
                "GET /api/v1/ops/courses/{courseId}",
                "PUT /api/v1/ops/courses/{courseId}",
                "PUT /api/v1/ops/courses/{courseId}/outline",
                "POST /api/v1/ops/courses/{courseId}/list",
                "POST /api/v1/ops/courses/{courseId}/unlist",
                "GET /api/v1/ops/courses/{courseId}/changes");
        assertThat(contract.declaredKeys()).containsExactlyInAnyOrderElementsOf(
                operations.stream().map(Operation::key).toList());
        assertThat(operations).allSatisfy(operation -> {
            Map<String, Object> declared = contract.declared(operation);
            assertThat(declared.get("security")).as(operation.key())
                    .isEqualTo(List.of(Map.of("bearerAuth", List.of())));
            Map<String, Object> responses = CourseContract.map(declared.get("responses"));
            assertThat(responses).as(operation.key()).containsKeys("400", "401", "403");
            if (operation.path().contains("{")) {
                assertThat(responses).as(operation.key()).containsKey("404");
            }
        });
    }

    @Test
    void parametersBodiesFieldsAndTypesMatchTheJavaSide() {
        contract.assertParameters();
        contract.assertBodies(REQUESTS);
        Map<String, Class<?>> all = new LinkedHashMap<>(RECORDS);
        all.putAll(REQUESTS);
        contract.assertFields(all, Map.of(
                "OpsCourseCreateRequest", List.of("mode", "topic", "title", "organizationId"),
                "OpsCourseRequest", List.of("version", "topic", "title", "organizationId", "sortOrder"),
                "OpsCourseIncludeRequest", List.of("name", "quantity"),
                "OpsCourseSpecRequest", List.of("label", "value"),
                "OpsCourseOutlineRequest", List.of("version", "chapters"),
                "OpsCourseChapterRequest", List.of("title", "lessons"),
                "OpsCourseLessonRequest", List.of("title", "durationSeconds", "trial"),
                "OpsCourseVersionRequest", List.of("version")));
        // OpsChange 的 subjectType、action 是内联枚举（和活动的写法一样），这里单独核对。
        Map<String, Class<?>> typed = new LinkedHashMap<>(all);
        typed.remove("OpsChange");
        contract.assertTypes(typed);
        contract.assertEnums();
        Map<String, Object> change = CourseContract.map(CourseContract.map(contract.schemas().get("OpsChange")).get("properties"));
        assertThat(CourseContract.strings(CourseContract.map(change.get("action")).get("enum")))
                .containsExactly("CREATE", "UPDATE", "OUTLINE", "LIST", "UNLIST");
    }

    @Test
    void everyErrorCodeIsDeclaredUnderItsStatus() {
        Set<String> produced = new TreeSet<>();
        for (CourseOpsError error : CourseOpsError.values()) {
            if (error.code().startsWith("COMMON_")) {
                continue;
            }
            produced.add(error.code());
            assertThat(contract.descriptionsUnder(String.valueOf(error.httpStatus()))).as(error.code())
                    .anyMatch(description -> description.contains(error.code()));
        }
        assertThat(CourseContract.strings(CourseContract.map(contract.schemas().get("OpsCourseErrorCode")).get("enum")))
                .containsExactlyInAnyOrderElementsOf(produced);
        Set<String> mentioned = new TreeSet<>();
        Matcher matcher = Pattern.compile("OPS_[A-Z_]+").matcher(contract.text());
        while (matcher.find()) {
            mentioned.add(matcher.group());
        }
        assertThat(mentioned).containsExactlyInAnyOrderElementsOf(produced);
        assertThat(Arrays.stream(OpsCourseListField.values()).map(Enum::name))
                .allSatisfy(field -> assertThat(contract.text()).contains(field));
    }

    @Test
    void everySchemaInTheContractIsCoveredByThisTest() {
        Set<String> covered = new TreeSet<>(RECORDS.keySet());
        covered.addAll(REQUESTS.keySet());
        covered.addAll(ENUMS.keySet());
        covered.add("OpsCourseErrorCode");
        covered.add("ApiError");
        assertThat(contract.schemas().keySet()).containsExactlyInAnyOrderElementsOf(covered);
    }
}
