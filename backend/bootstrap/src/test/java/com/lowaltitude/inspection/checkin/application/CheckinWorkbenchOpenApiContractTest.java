package com.lowaltitude.inspection.checkin.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.activity.application.ActivityCheckinException;
import com.lowaltitude.inspection.bootstrap.api.ApiError;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.experience.application.VenueCheckinException;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.yaml.snakeyaml.Yaml;

/**
 * 契约 openapi-checkin-workbench.yaml 和控制器逐项对照：路由、参数、请求体、返回对象、字段和类型、枚举、错误码。
 * 改了一边忘了另一边会立刻失败。
 */
class CheckinWorkbenchOpenApiContractTest {

    private static final Path CONTRACT = Path.of("..", "..", "contracts", "openapi-checkin-workbench.yaml");
    private static final String SCHEMA_PREFIX = "#/components/schemas/";

    private static final Map<String, Class<? extends Record>> SCHEMA_RECORDS = Map.of(
            "CheckinWorkbench", CheckinWorkbenchView.class,
            "CheckinSession", CheckinSessionView.class,
            "CheckinSessionRef", CheckinSessionRefView.class,
            "CheckinRecordList", CheckinRecordListView.class,
            "CheckinRecord", CheckinRecordView.class,
            "CheckinResolution", CheckinResolutionView.class);

    private static final Map<String, Class<? extends Record>> REQUEST_RECORDS = Map.of(
            "VerifyRequest", CheckinWorkbenchController.VerifyRequest.class,
            "ResolveRequest", CheckinWorkbenchController.ResolveRequest.class);

    private static final Map<String, List<String>> REQUEST_REQUIRED = Map.of(
            "VerifyRequest", List.of("code", "method"),
            "ResolveRequest", List.of("action"));

    private static final Map<String, Class<? extends Enum<?>>> SCHEMA_ENUMS = Map.of(
            "CheckinKind", CheckinKind.class,
            "CheckinPhase", CheckinPhase.class,
            "CheckinOutcome", CheckinOutcome.class,
            "CheckinMethod", CheckinMethod.class,
            "CheckinAction", CheckinAction.class);

    @Test
    void contractIsVersionOneAndEveryRouteNeedsALogin() {
        assertThat(map(root().get("info")).get("version")).isEqualTo("1.0.0");
        assertThat(root()).doesNotContainKey("security");

        assertThat(operations()).extracting(Operation::key).containsExactlyInAnyOrder(
                "GET /api/v1/checkin-workbench",
                "GET /api/v1/checkin-workbench/records",
                "GET /api/v1/checkin-workbench/records/{kind}/{recordId}",
                "POST /api/v1/checkin-workbench/verifications",
                "POST /api/v1/checkin-workbench/records/{kind}/{recordId}/resolution");
        assertThat(operations()).allSatisfy(operation -> {
            Map<String, Object> declared = declared(operation);
            assertThat(declared).as(operation.key()).isNotNull();
            assertThat(declared.get("security")).as(operation.key())
                    .isEqualTo(List.of(Map.of("bearerAuth", List.of())));
            assertThat(map(declared.get("responses")).keySet()).as(operation.key()).contains("400", "401", "403");
        });
        Set<String> declared = new TreeSet<>();
        map(root().get("paths")).forEach((path, item) -> map(item).keySet()
                .forEach(method -> declared.add(method.toUpperCase() + " " + path)));
        assertThat(declared).containsExactlyInAnyOrderElementsOf(operations().stream().map(Operation::key).toList());
    }

    @Test
    void everyParameterAndBodyIsDeclaredAndPointsAtItsSchema() {
        assertThat(operations()).allSatisfy(operation -> {
            Map<String, Object> declared = declared(operation);
            Set<String> declaredParameters = new TreeSet<>();
            for (Object entry : list(declared.get("parameters"))) {
                Map<String, Object> parameter = resolve(map(entry));
                declaredParameters.add(parameter.get("in") + " " + parameter.get("name"));
            }
            Set<String> implemented = new TreeSet<>();
            Parameter body = null;
            for (Parameter parameter : operation.method().getParameters()) {
                if (parameter.isAnnotationPresent(RequestParam.class)) {
                    implemented.add("query " + parameter.getName());
                }
                if (parameter.isAnnotationPresent(PathVariable.class)) {
                    implemented.add("path " + parameter.getName());
                }
                if (parameter.isAnnotationPresent(RequestBody.class)) {
                    body = parameter;
                }
            }
            assertThat(declaredParameters).as(operation.key()).containsExactlyInAnyOrderElementsOf(implemented);
            if (body == null) {
                assertThat(declared).as(operation.key()).doesNotContainKey("requestBody");
            } else {
                assertThat(schemaRef(map(declared.get("requestBody")))).as(operation.key())
                        .isEqualTo(SCHEMA_PREFIX + schemaOf(REQUEST_RECORDS, body.getType()));
            }
            Map<String, Object> responses = map(declared.get("responses"));
            assertThat(schemaRef(map(responses.get("200")))).as(operation.key())
                    .isEqualTo(SCHEMA_PREFIX + schemaOf(SCHEMA_RECORDS, operation.method().getReturnType()));
        });
    }

    @Test
    void everyObjectHasTheFieldsOfItsJavaRecordWithTheirTypes() {
        Map<String, Object> schemas = schemas();
        SCHEMA_RECORDS.forEach((name, type) -> {
            Map<String, Object> schema = map(schemas.get(name));
            List<String> fields = Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
            assertThat(schema.get("additionalProperties")).as(name).isEqualTo(false);
            assertThat(map(schema.get("properties")).keySet()).as(name).containsExactlyInAnyOrderElementsOf(fields);
            assertThat(strings(schema.get("required"))).as(name).containsExactlyInAnyOrderElementsOf(fields);
        });
        REQUEST_RECORDS.forEach((name, type) -> {
            Map<String, Object> schema = map(schemas.get(name));
            List<String> fields = Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
            assertThat(schema.get("additionalProperties")).as(name).isEqualTo(false);
            assertThat(map(schema.get("properties")).keySet()).as(name).containsExactlyInAnyOrderElementsOf(fields);
            assertThat(strings(schema.get("required"))).as(name).containsExactlyInAnyOrderElementsOf(REQUEST_REQUIRED.get(name));
        });

        List<Class<? extends Record>> all = new ArrayList<>(SCHEMA_RECORDS.values());
        all.addAll(REQUEST_RECORDS.values());
        for (Class<? extends Record> type : all) {
            String name = schemaOf(SCHEMA_RECORDS.containsValue(type) ? SCHEMA_RECORDS : REQUEST_RECORDS, type);
            Map<String, Object> properties = map(map(schemas.get(name)).get("properties"));
            for (RecordComponent component : type.getRecordComponents()) {
                String field = name + "." + component.getName();
                Map<String, Object> property = map(properties.get(component.getName()));
                Class<?> javaType = component.getType();
                if (javaType.isRecord() || javaType.isEnum()) {
                    String expected = SCHEMA_PREFIX + (javaType.isRecord()
                            ? schemaOf(SCHEMA_RECORDS, javaType)
                            : schemaOf(SCHEMA_ENUMS, javaType));
                    assertThat(refOf(property)).as(field).isEqualTo(expected);
                    continue;
                }
                assertThat(types(property)).as(field).contains(jsonType(javaType));
                if (javaType.isPrimitive()) {
                    assertThat(types(property)).as(field).doesNotContain("null");
                }
                if (javaType == UUID.class || javaType == OffsetDateTime.class || javaType == LocalDate.class) {
                    assertThat(property.get("format")).as(field).isEqualTo(
                            javaType == UUID.class ? "uuid" : javaType == LocalDate.class ? "date" : "date-time");
                }
                if (javaType == List.class) {
                    Class<?> element = (Class<?>) ((ParameterizedType) component.getGenericType()).getActualTypeArguments()[0];
                    String expected = SCHEMA_PREFIX + (element.isRecord()
                            ? schemaOf(SCHEMA_RECORDS, element)
                            : schemaOf(SCHEMA_ENUMS, element));
                    assertThat(map(property.get("items")).get("$ref")).as(field).isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void enumsMatchAndEverySchemaIsCovered() {
        Map<String, Object> schemas = schemas();
        SCHEMA_ENUMS.forEach((name, type) -> assertThat(strings(map(schemas.get(name)).get("enum"))).as(name)
                .containsExactlyElementsOf(Arrays.stream(type.getEnumConstants()).map(Enum::name).toList()));

        List<String> covered = new ArrayList<>(SCHEMA_RECORDS.keySet());
        covered.addAll(REQUEST_RECORDS.keySet());
        covered.addAll(SCHEMA_ENUMS.keySet());
        covered.add("CheckinErrorCode");
        covered.add("ApiError");
        assertThat(schemas.keySet()).containsExactlyInAnyOrderElementsOf(covered);
    }

    // 错误码：被拦下的六种来自记录的结果，其余来自两个模块的拒绝；两个模块给出同一套码，契约里提到的都真会给出。
    @Test
    void everyErrorCodeTheHandlerGivesIsDeclaredAndNothingElse() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Set<String> produced = new TreeSet<>();
        for (CheckinOutcome outcome : CheckinOutcome.values()) {
            if (outcome != CheckinOutcome.CHECKED_IN) {
                produced.add("CHECKIN_" + outcome.name());
            }
        }
        for (ActivityCheckinException.Reason reason : ActivityCheckinException.Reason.values()) {
            ResponseEntity<ApiError> activity = handler.handleActivityCheckin(
                    new ActivityCheckinException(reason), new MockHttpServletRequest());
            ResponseEntity<ApiError> venue = handler.handleVenueCheckin(
                    new VenueCheckinException(VenueCheckinException.Reason.valueOf(reason.name())),
                    new MockHttpServletRequest());
            assertThat(venue.getStatusCode()).as(reason.name()).isEqualTo(activity.getStatusCode());
            assertThat(venue.getBody().code()).as(reason.name()).isEqualTo(activity.getBody().code());
            String code = activity.getBody().code();
            if (code.startsWith("CHECKIN_")) {
                assertThat(activity.getStatusCode().value()).as(code).isEqualTo(409);
                produced.add(code);
            } else {
                assertThat(code).isIn("COMMON_NOT_FOUND", "COMMON_FORBIDDEN");
            }
        }
        assertThat(VenueCheckinException.Reason.values()).hasSameSizeAs(ActivityCheckinException.Reason.values());

        assertThat(strings(map(schemas().get("CheckinErrorCode")).get("enum"))).containsExactlyInAnyOrderElementsOf(produced);
        Set<String> mentioned = new TreeSet<>();
        Matcher matcher = Pattern.compile("CHECKIN_[A-Z_]+").matcher(contractText());
        while (matcher.find()) {
            mentioned.add(matcher.group());
        }
        assertThat(mentioned).containsExactlyInAnyOrderElementsOf(produced);
    }

    // ---- 控制器一侧 ----

    private record Operation(String httpMethod, String path, Method method) {
        String key() {
            return httpMethod + " " + path;
        }
    }

    private static List<Operation> operations() {
        List<Operation> operations = new ArrayList<>();
        for (Method method : CheckinWorkbenchController.class.getDeclaredMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping != null) {
                operations.add(new Operation(mapping.method()[0].name(), mapping.path()[0], method));
            }
        }
        return operations;
    }

    private static String schemaOf(Map<String, ? extends Class<?>> registered, Class<?> type) {
        return registered.entrySet().stream()
                .filter(entry -> entry.getValue() == type)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError(type.getSimpleName() + " has no schema in this test"));
    }

    private static String jsonType(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return "integer";
        }
        if (type == String.class || type == UUID.class || type == OffsetDateTime.class || type == LocalDate.class) {
            return "string";
        }
        if (type == List.class) {
            return "array";
        }
        throw new AssertionError("no JSON type rule for " + type.getName());
    }

    // ---- 契约一侧 ----

    private static String contractText() {
        try {
            return Files.readString(CONTRACT);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static Map<String, Object> root() {
        return new Yaml().load(contractText());
    }

    private static Map<String, Object> schemas() {
        return map(map(root().get("components")).get("schemas"));
    }

    private static Map<String, Object> declared(Operation operation) {
        Map<String, Object> item = map(map(root().get("paths")).get(operation.path()));
        return item == null ? null : map(item.get(operation.httpMethod().toLowerCase()));
    }

    private static Map<String, Object> resolve(Map<String, Object> parameter) {
        Object ref = parameter.get("$ref");
        if (ref == null) {
            return parameter;
        }
        String name = ((String) ref).substring("#/components/parameters/".length());
        return map(map(map(root().get("components")).get("parameters")).get(name));
    }

    private static String schemaRef(Map<String, Object> bodyOrResponse) {
        Map<String, Object> content = map(bodyOrResponse.get("content"));
        return (String) map(map(content.get("application/json")).get("schema")).get("$ref");
    }

    private static String refOf(Map<String, Object> property) {
        if (property.get("$ref") != null) {
            return (String) property.get("$ref");
        }
        return list(property.get("anyOf")).stream()
                .map(option -> (String) map(option).get("$ref"))
                .filter(ref -> ref != null)
                .findFirst()
                .orElse(null);
    }

    private static List<String> types(Map<String, Object> property) {
        Object type = property.get("type");
        return type instanceof String single ? List.of(single) : strings(type);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return (List<String>) value;
    }
}
