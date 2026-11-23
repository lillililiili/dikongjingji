package com.lowaltitude.inspection.favorite.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.favorite.application.FavoriteException.Error;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.yaml.snakeyaml.Yaml;

/**
 * 契约 contracts/openapi-favorite.yaml 和实现逐项对照（计划 08 S5-6）：每个路由都在契约里并且要登录；参数、返回对象指向
 * 对应的 schema；每个对象的字段、类型、枚举与 Java 一致；上限与服务一致；业务错误码与全局异常处理给出的一致。
 */
class FavoriteOpenApiContractTest {

    private static final Path CONTRACT = Path.of("..", "..", "contracts", "openapi-favorite.yaml");
    private static final String SCHEMA_PREFIX = "#/components/schemas/";

    private static final Map<String, Class<? extends Record>> SCHEMA_RECORDS = Map.of(
            "FavoriteState", FavoriteStateView.class,
            "FavoritePrice", FavoritePriceView.class,
            "FavoriteItem", FavoriteItemView.class,
            "FavoriteCounts", FavoriteCountsView.class,
            "FavoritePage", FavoritePageView.class);

    private static final Map<String, Class<? extends Enum<?>>> SCHEMA_ENUMS = Map.of(
            "FavoriteTargetType", FavoriteTargetType.class,
            "FavoriteGroup", FavoriteGroup.class,
            "FavoritePriceMode", FavoritePorts.PriceMode.class);

    /** 按字符串收、契约里引用枚举的参数。 */
    private static final Map<String, String> ENUM_PARAMETERS = Map.of(
            "path targetType", "FavoriteTargetType",
            "query group", "FavoriteGroup");

    private static final List<Class<?>> CONTROLLERS = List.of(FavoriteController.class);

    @Test
    void contractIsVersionOneAndDeclaresBearerJwt() {
        Map<String, Object> root = root();

        assertThat(map(root.get("info")).get("version")).isEqualTo("1.0.0");
        assertThat(map(map(root.get("components")).get("securitySchemes")).get("bearerAuth"))
                .isEqualTo(Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT"));
        assertThat(root).doesNotContainKey("security");
    }

    @Test
    void everyRouteIsDeclaredWithItsMethodAndNeedsALogin() {
        List<Operation> operations = allOperations();

        assertThat(operations).extracting(Operation::key).containsExactlyInAnyOrder(
                "GET /api/v1/favorites",
                "GET /api/v1/favorites/{targetType}/{targetId}",
                "PUT /api/v1/favorites/{targetType}/{targetId}",
                "DELETE /api/v1/favorites/{targetType}/{targetId}");
        assertThat(operations).allSatisfy(operation -> {
            Map<String, Object> declared = declared(operation);
            assertThat(declared).as(operation.key()).isNotNull();
            assertThat(declared.get("security")).as(operation.key())
                    .isEqualTo(List.of(Map.of("bearerAuth", List.of())));
            assertThat(map(declared.get("responses")).keySet()).as(operation.key()).contains("400", "401", "403");
            if (operation.path().contains("{")) {
                assertThat(map(declared.get("responses"))).as(operation.key()).containsKey("404");
            }
        });
        Set<String> declaredKeys = new TreeSet<>();
        map(root().get("paths")).forEach((path, item) -> map(item).keySet()
                .forEach(method -> declaredKeys.add(method.toUpperCase() + " " + path)));
        assertThat(declaredKeys).hasSize(4);
    }

    @Test
    void everyParameterOfEveryRouteIsDeclaredInTheSamePlace() {
        assertThat(allOperations()).allSatisfy(operation -> {
            Map<String, Map<String, Object>> declared = new LinkedHashMap<>();
            for (Object entry : list(declared(operation).get("parameters"))) {
                Map<String, Object> parameter = resolve(map(entry));
                declared.put(parameter.get("in") + " " + parameter.get("name"), parameter);
            }
            Set<String> implemented = new TreeSet<>();
            for (Parameter parameter : operation.method().getParameters()) {
                RequestParam query = parameter.getAnnotation(RequestParam.class);
                PathVariable path = parameter.getAnnotation(PathVariable.class);
                assertThat(parameter.getAnnotation(RequestHeader.class)).as(operation.key()).isNull();
                assertThat(parameter.getAnnotation(RequestBody.class)).as(operation.key()).isNull();
                String key = null;
                if (query != null) {
                    key = "query " + name(query.name(), query.value(), parameter);
                    assertThat(declared.get(key).get("required")).as(key)
                            .isEqualTo(query.required() && query.defaultValue().equals(
                                    org.springframework.web.bind.annotation.ValueConstants.DEFAULT_NONE));
                }
                if (path != null) {
                    key = "path " + name(path.name(), path.value(), parameter);
                }
                if (key == null) {
                    continue;
                }
                implemented.add(key);
                Map<String, Object> schema = map(declared.get(key).get("schema"));
                if (ENUM_PARAMETERS.containsKey(key)) {
                    assertThat(parameter.getType()).as(key).isEqualTo(String.class);
                    assertThat(schema.get("$ref")).as(key).isEqualTo(SCHEMA_PREFIX + ENUM_PARAMETERS.get(key));
                } else if (parameter.getType() == UUID.class) {
                    assertThat(schema).as(key).containsEntry("format", "uuid");
                } else if (parameter.getType() == int.class) {
                    assertThat(schema).as(key).containsEntry("type", "integer");
                } else {
                    assertThat(schema).as(key).containsEntry("type", "string");
                }
            }
            assertThat(declared.keySet()).as(operation.key()).containsExactlyInAnyOrderElementsOf(implemented);
        });
        Map<String, Object> size = map(map(map(root().get("components")).get("parameters")).get("Size"));
        assertThat(map(size.get("schema")).get("maximum")).isEqualTo(FavoriteService.MAX_PAGE_SIZE);
        assertThat(map(size.get("schema")).get("default")).isEqualTo(FavoriteService.DEFAULT_PAGE_SIZE);
        assertThat(contractText()).doesNotContain("ETag").doesNotContain("If-Match").doesNotContain("requestBody");
    }

    @Test
    void everySuccessResponsePointsAtTheSchemaOfItsJavaType() {
        assertThat(allOperations()).allSatisfy(operation -> {
            Map<String, Object> responses = map(declared(operation).get("responses"));
            List<String> success = responses.keySet().stream().filter(code -> code.startsWith("2")).toList();
            Class<?> returned = returnedBody(operation.method());
            assertThat(success).as(operation.key()).isNotEmpty();
            assertThat(success).allSatisfy(code -> assertThat(jsonSchemaRef(map(responses.get(code))))
                    .as(operation.key() + " " + code)
                    .isEqualTo(SCHEMA_PREFIX + schemaOf(SCHEMA_RECORDS, returned)));
        });
        assertThat(successCodes("put", "/api/v1/favorites/{targetType}/{targetId}"))
                .containsExactlyInAnyOrder("201", "200");
        assertThat(successCodes("delete", "/api/v1/favorites/{targetType}/{targetId}")).containsExactly("200");
    }

    @Test
    void everyResponseObjectHasExactlyTheFieldsTheContractRequires() {
        Map<String, Object> schemas = schemas();

        assertThat(SCHEMA_RECORDS).allSatisfy((schemaName, recordType) -> {
            Map<String, Object> schema = map(schemas.get(schemaName));
            List<String> fields = fields(recordType);
            assertThat(schema).as(schemaName).isNotNull();
            assertThat(schema.get("additionalProperties")).as(schemaName).isEqualTo(false);
            assertThat(map(schema.get("properties")).keySet()).as(schemaName)
                    .containsExactlyInAnyOrderElementsOf(fields);
            assertThat(strings(schema.get("required"))).as(schemaName).containsExactlyInAnyOrderElementsOf(fields);
        });
    }

    @Test
    void everyFieldIsDeclaredWithTheTypeOfItsJavaComponent() {
        Map<String, Object> schemas = schemas();

        assertThat(SCHEMA_RECORDS).allSatisfy((schemaName, recordType) -> {
            Map<String, Object> properties = map(map(schemas.get(schemaName)).get("properties"));
            for (RecordComponent component : recordType.getRecordComponents()) {
                String field = schemaName + "." + component.getName();
                Map<String, Object> property = map(properties.get(component.getName()));
                Class<?> type = component.getType();
                if (type.isRecord() || type.isEnum()) {
                    String expected = SCHEMA_PREFIX + (type.isRecord()
                            ? schemaOf(SCHEMA_RECORDS, type)
                            : schemaOf(SCHEMA_ENUMS, type));
                    assertThat(refOf(property)).as(field).isEqualTo(expected);
                    continue;
                }
                List<String> declaredTypes = types(property);
                assertThat(declaredTypes).as(field + " type").contains(jsonType(type));
                if (type.isPrimitive()) {
                    assertThat(declaredTypes).as(field + " is never null").doesNotContain("null");
                }
                if (type == UUID.class) {
                    assertThat(property.get("format")).as(field).isEqualTo("uuid");
                }
                if (type == OffsetDateTime.class) {
                    assertThat(property.get("format")).as(field).isEqualTo("date-time");
                }
                if (type == List.class) {
                    Class<?> element = (Class<?>) ((ParameterizedType) component.getGenericType())
                            .getActualTypeArguments()[0];
                    assertThat(map(property.get("items")).get("$ref")).as(field + " items")
                            .isEqualTo(SCHEMA_PREFIX + schemaOf(SCHEMA_RECORDS, element));
                }
            }
        });
    }

    @Test
    void enumValuesMatchTheContract() {
        Map<String, Object> schemas = schemas();

        assertThat(SCHEMA_ENUMS).allSatisfy((schemaName, enumType) ->
                assertThat(strings(map(schemas.get(schemaName)).get("enum"))).as(schemaName)
                        .containsExactlyElementsOf(names(enumType)));
        // 商品的类目和商城的类目一致（App 只让同一类目的商品对比）。
        List<Object> categories = new ArrayList<>(list(map(map(map(schemas.get("FavoriteItem")).get("properties"))
                .get("productCategory")).get("enum")));
        categories.remove(null);
        assertThat(categories).containsExactlyElementsOf(
                names(com.lowaltitude.inspection.mall.application.MallCategory.class));
    }

    @Test
    void limitsInTheContractAreTheOnesTheServiceEnforces() {
        Map<String, Object> item = map(map(schemas().get("FavoriteItem")).get("properties"));
        assertThat(map(item.get("title")).get("maxLength")).isEqualTo(FavoriteService.MAX_TITLE);
        assertThat(contractText())
                .contains("最多 " + FavoriteService.MAX_FAVORITES + " 条收藏")
                .contains(FavoriteService.UNDO_WINDOW.toMinutes() + " 分钟内再收藏");
    }

    @Test
    void everyBusinessErrorCodeIsDeclaredUnderTheStatusTheHandlerAnswers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Set<String> produced = new TreeSet<>();
        for (Error error : Error.values()) {
            ResponseEntity<ApiError> response = handler.handleFavorite(new FavoriteException(error),
                    new MockHttpServletRequest());
            String code = response.getBody().code();
            String status = String.valueOf(response.getStatusCode().value());
            if (!code.startsWith("FAVORITE_")) {
                assertThat(code).as(error.name()).isEqualTo("COMMON_FORBIDDEN");
                assertThat(status).isEqualTo("403");
                continue;
            }
            produced.add(code);
            assertThat(allOperations().stream()
                    .map(operation -> resolveResponse(map(map(declared(operation).get("responses")).get(status))))
                    .filter(declaredResponse -> declaredResponse != null && declaredResponse.get("description") != null)
                    .map(declaredResponse -> (String) declaredResponse.get("description")))
                    .as(code + " under " + status)
                    .anyMatch(description -> description.contains(code));
        }
        assertThat(strings(map(schemas().get("FavoriteErrorCode")).get("enum")))
                .containsExactlyInAnyOrderElementsOf(produced);
        Set<String> mentioned = new TreeSet<>();
        Matcher matcher = Pattern.compile("FAVORITE_[A-Z_]+").matcher(contractText());
        while (matcher.find()) {
            mentioned.add(matcher.group());
        }
        assertThat(mentioned).containsExactlyElementsOf(produced);
    }

    @Test
    void everySchemaInTheContractIsCoveredByThisTest() {
        List<String> covered = new ArrayList<>(SCHEMA_RECORDS.keySet());
        covered.addAll(SCHEMA_ENUMS.keySet());
        covered.add("FavoriteErrorCode");
        covered.add("ApiError");

        assertThat(schemas().keySet()).containsExactlyInAnyOrderElementsOf(covered);
    }

    // ---- 控制器一侧 ----

    private record Operation(String httpMethod, String path, Method method) {
        String key() {
            return httpMethod + " " + path;
        }
    }

    private static List<Operation> allOperations() {
        List<Operation> operations = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping != null) {
                    assertThat(mapping.path()).as(method.getName()).hasSize(1);
                    assertThat(mapping.method()).as(method.getName()).hasSize(1);
                    operations.add(new Operation(mapping.method()[0].name(), mapping.path()[0], method));
                }
            }
        }
        return operations;
    }

    private static String name(String name, String value, Parameter parameter) {
        if (!name.isEmpty()) {
            return name;
        }
        return value.isEmpty() ? parameter.getName() : value;
    }

    private static Class<?> returnedBody(Method method) {
        Type type = method.getGenericReturnType();
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == ResponseEntity.class) {
            return (Class<?>) parameterized.getActualTypeArguments()[0];
        }
        return method.getReturnType();
    }

    private static List<String> fields(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static List<String> names(Class<? extends Enum<?>> type) {
        return Arrays.stream(type.getEnumConstants()).map(Enum::name).toList();
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
        if (type == String.class || type == UUID.class || type == OffsetDateTime.class) {
            return "string";
        }
        if (type == List.class) {
            return "array";
        }
        throw new AssertionError("no JSON type rule for " + type.getName());
    }

    // ---- 契约一侧 ----

    private static String contractText() {
        return read(CONTRACT);
    }

    private static String read(Path contract) {
        try {
            return Files.readString(contract);
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

    private static List<String> successCodes(String httpMethod, String path) {
        Map<String, Object> responses = map(map(map(map(root().get("paths")).get(path)).get(httpMethod)).get("responses"));
        return responses.keySet().stream().filter(code -> code.startsWith("2")).toList();
    }

    private static Map<String, Object> resolve(Map<String, Object> parameter) {
        Object ref = parameter.get("$ref");
        if (ref == null) {
            return parameter;
        }
        String name = ((String) ref).substring("#/components/parameters/".length());
        return map(map(map(root().get("components")).get("parameters")).get(name));
    }

    /** 响应可以直接写，也可以引用 components.responses 里的。 */
    private static Map<String, Object> resolveResponse(Map<String, Object> response) {
        if (response == null || response.get("$ref") == null) {
            return response;
        }
        String name = ((String) response.get("$ref")).substring("#/components/responses/".length());
        return map(map(map(root().get("components")).get("responses")).get(name));
    }

    private static String jsonSchemaRef(Map<String, Object> bodyOrResponse) {
        Map<String, Object> content = map(bodyOrResponse.get("content"));
        return (String) map(map(content.get("application/json")).get("schema")).get("$ref");
    }

    private static String refOf(Map<String, Object> property) {
        if (property.get("$ref") != null) {
            return (String) property.get("$ref");
        }
        return list(property.get("anyOf")).stream()
                .map(option -> (String) map(option).get("$ref"))
                .filter(Objects::nonNull)
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
