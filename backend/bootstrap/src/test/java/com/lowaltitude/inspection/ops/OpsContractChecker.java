package com.lowaltitude.inspection.ops;

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
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.yaml.snakeyaml.Yaml;

/**
 * 运营端契约（contracts/openapi-ops-*.yaml）和 ops 控制器的逐项对照，给每块运营功能的契约测试共用（S3-1）。
 * 新加一块运营功能时：写契约、写控制器，然后照 OpsCityOpenApiContractTest 写一个测试，把控制器、schema 和 Java 记录、
 * 请求记录、枚举的对应关系交给这里。检查：
 * <ul>
 *   <li>每个路由都在契约里、都要 bearer 登录、都声明了 401 和 403，带路径参数的声明了 404；契约里没有多出来的路由；</li>
 *   <li>路径、查询、请求头参数两边一致；请求体和成功响应指向对应的 schema；</li>
 *   <li>每个响应对象的字段正好是 Java 记录的字段，全部 required，类型一致（可空的写成 [x, "null"]）；</li>
 *   <li>请求对象的字段和 required 与控制器的请求记录一致；枚举的值和 Java 枚举一致；</li>
 *   <li>契约里提到的 OPS_ 错误码正好是 &lt;前缀&gt;ErrorCode 里列的。</li>
 * </ul>
 */
public final class OpsContractChecker {

    private static final String SCHEMA_PREFIX = "#/components/schemas/";

    private final Path contract;
    private final List<Class<?>> controllers;
    private final Map<String, Class<?>> responseRecords = new LinkedHashMap<>();
    private final Map<String, Class<?>> requestRecords = new LinkedHashMap<>();
    private final Map<String, List<String>> requestRequired = new LinkedHashMap<>();
    private final Map<String, String> requestEnumFields = new LinkedHashMap<>();
    private final Map<String, Class<?>> enums = new LinkedHashMap<>();
    private final Set<String> otherSchemas = new TreeSet<>();
    private Map<String, Object> root;

    public OpsContractChecker(String contractFile, Class<?>... controllers) {
        this.contract = Path.of("..", "..", "contracts", contractFile);
        this.controllers = List.of(controllers);
    }

    public OpsContractChecker response(String schema, Class<?> recordType) {
        responseRecords.put(schema, recordType);
        return this;
    }

    public OpsContractChecker request(String schema, Class<?> recordType, String... required) {
        requestRecords.put(schema, recordType);
        requestRequired.put(schema, List.of(required));
        return this;
    }

    /** 请求里按字符串收、契约里引用枚举的字段，写成 “Schema.field”。 */
    public OpsContractChecker requestEnumField(String schemaField, String enumSchema) {
        requestEnumFields.put(schemaField, enumSchema);
        return this;
    }

    public OpsContractChecker enumeration(String schema, Class<?> enumType) {
        enums.put(schema, enumType);
        return this;
    }

    /** 不对应 Java 类型的 schema（ApiError、错误码枚举等）。 */
    public OpsContractChecker other(String... schemas) {
        otherSchemas.addAll(List.of(schemas));
        return this;
    }

    public String text() {
        try {
            return Files.readString(contract);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    public Map<String, Object> root() {
        if (root == null) {
            root = new Yaml().load(text());
        }
        return root;
    }

    public Map<String, Object> schemas() {
        return map(map(root().get("components")).get("schemas"));
    }

    /** 全部检查。 */
    public void verifyAll(String version) {
        assertThat(map(root().get("info")).get("version")).isEqualTo(version);
        assertThat(map(map(map(root().get("components")).get("securitySchemes")).get("bearerAuth")))
                .isEqualTo(Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT"));
        verifyRoutes();
        verifyParameters();
        verifyBodies();
        verifyResponseObjects();
        verifyRequestObjects();
        verifyFieldTypes();
        verifyEnums();
        verifyEverySchemaIsCovered();
    }

    public void verifyRoutes() {
        List<Operation> operations = operations();
        assertThat(operations).isNotEmpty();
        assertThat(operations).allSatisfy(operation -> {
            assertThat(operation.path()).as(operation.key()).startsWith("/api/v1/ops/");
            Map<String, Object> declared = declared(operation);
            assertThat(declared).as(operation.key()).isNotNull();
            assertThat(declared.get("security")).as(operation.key())
                    .isEqualTo(List.of(Map.of("bearerAuth", List.of())));
            assertThat(map(declared.get("responses")).keySet()).as(operation.key()).contains("401", "403");
            if (operation.path().contains("{")) {
                assertThat(map(declared.get("responses"))).as(operation.key()).containsKey("404");
            }
        });
        Set<String> declaredKeys = new TreeSet<>();
        map(root().get("paths")).forEach((path, item) -> map(item).keySet()
                .forEach(method -> declaredKeys.add(method.toUpperCase() + " " + path)));
        assertThat(declaredKeys).containsExactlyInAnyOrderElementsOf(
                operations.stream().map(Operation::key).toList());
    }

    public void verifyParameters() {
        assertThat(operations()).allSatisfy(operation -> {
            Set<String> declared = new TreeSet<>();
            for (Object entry : list(declared(operation).get("parameters"))) {
                Map<String, Object> parameter = resolve(map(entry));
                declared.add(parameter.get("in") + " " + parameter.get("name"));
            }
            Set<String> implemented = new TreeSet<>();
            for (Parameter parameter : operation.method().getParameters()) {
                RequestParam query = parameter.getAnnotation(RequestParam.class);
                PathVariable path = parameter.getAnnotation(PathVariable.class);
                RequestHeader header = parameter.getAnnotation(RequestHeader.class);
                if (query != null) {
                    implemented.add("query " + name(query.name(), query.value(), parameter));
                }
                if (path != null) {
                    implemented.add("path " + name(path.name(), path.value(), parameter));
                }
                if (header != null) {
                    implemented.add("header " + name(header.name(), header.value(), parameter));
                }
            }
            assertThat(declared).as(operation.key()).containsExactlyInAnyOrderElementsOf(implemented);
        });
    }

    public void verifyBodies() {
        assertThat(operations()).allSatisfy(operation -> {
            Map<String, Object> declared = declared(operation);
            Parameter body = Arrays.stream(operation.method().getParameters())
                    .filter(parameter -> parameter.isAnnotationPresent(RequestBody.class))
                    .findFirst().orElse(null);
            boolean multipart = Arrays.stream(operation.method().getParameters())
                    .anyMatch(parameter -> parameter.isAnnotationPresent(RequestPart.class));
            if (body != null) {
                Map<String, Object> requestBody = map(declared.get("requestBody"));
                assertThat(requestBody.get("required")).as(operation.key()).isEqualTo(true);
                assertThat(jsonSchemaRef(requestBody)).as(operation.key())
                        .isEqualTo(SCHEMA_PREFIX + schemaOf(requestRecords, body.getType()));
            } else if (multipart) {
                Map<String, Object> content = map(map(declared.get("requestBody")).get("content"));
                assertThat(content).as(operation.key()).containsKey("multipart/form-data");
            } else {
                assertThat(declared).as(operation.key()).doesNotContainKey("requestBody");
            }
            Map<String, Object> responses = map(declared.get("responses"));
            List<String> success = responses.keySet().stream().filter(code -> code.startsWith("2")).toList();
            assertThat(success).as(operation.key()).isNotEmpty();
            Type returned = returnedBody(operation.method());
            if (returned == byte[].class) {
                assertThat(success).allSatisfy(code -> assertThat(map(map(responses.get(code)).get("content")))
                        .as(operation.key()).doesNotContainKey("application/json"));
                return;
            }
            for (String code : success) {
                Map<String, Object> schema = map(map(map(map(responses.get(code)).get("content"))
                        .get("application/json")).get("schema"));
                if (returned instanceof ParameterizedType parameterized && parameterized.getRawType() == List.class) {
                    assertThat(schema.get("type")).as(operation.key()).isEqualTo("array");
                    assertThat(map(schema.get("items")).get("$ref")).as(operation.key()).isEqualTo(SCHEMA_PREFIX
                            + schemaOf(responseRecords, (Class<?>) parameterized.getActualTypeArguments()[0]));
                } else {
                    assertThat(schema.get("$ref")).as(operation.key() + " " + code)
                            .isEqualTo(SCHEMA_PREFIX + schemaOf(responseRecords, (Class<?>) returned));
                }
            }
        });
    }

    public void verifyResponseObjects() {
        assertThat(responseRecords).allSatisfy((schemaName, recordType) -> {
            Map<String, Object> schema = map(schemas().get(schemaName));
            assertThat(schema).as(schemaName).isNotNull();
            assertThat(schema.get("additionalProperties")).as(schemaName).isEqualTo(false);
            assertThat(map(schema.get("properties")).keySet()).as(schemaName)
                    .containsExactlyInAnyOrderElementsOf(fields(recordType));
            assertThat(strings(schema.get("required"))).as(schemaName)
                    .containsExactlyInAnyOrderElementsOf(fields(recordType));
        });
    }

    public void verifyRequestObjects() {
        assertThat(requestRecords).allSatisfy((schemaName, recordType) -> {
            Map<String, Object> schema = map(schemas().get(schemaName));
            assertThat(schema).as(schemaName).isNotNull();
            assertThat(schema.get("additionalProperties")).as(schemaName).isEqualTo(false);
            assertThat(map(schema.get("properties")).keySet()).as(schemaName)
                    .containsExactlyInAnyOrderElementsOf(fields(recordType));
            assertThat(strings(schema.get("required"))).as(schemaName)
                    .containsExactlyInAnyOrderElementsOf(requestRequired.get(schemaName));
        });
        List<Class<?>> requestTypes = new ArrayList<>();
        for (Class<?> controller : controllers) {
            Arrays.stream(controller.getDeclaredClasses()).filter(Class::isRecord)
                    .filter(type -> !java.lang.reflect.Modifier.isPrivate(type.getModifiers()))
                    .forEach(requestTypes::add);
        }
        assertThat(requestTypes).containsExactlyInAnyOrderElementsOf(requestRecords.values());
    }

    public void verifyFieldTypes() {
        Map<String, Class<?>> records = new LinkedHashMap<>(responseRecords);
        records.putAll(requestRecords);
        assertThat(records).allSatisfy((schemaName, recordType) -> {
            Map<String, Object> properties = map(map(schemas().get(schemaName)).get("properties"));
            for (RecordComponent component : recordType.getRecordComponents()) {
                String field = schemaName + "." + component.getName();
                Map<String, Object> property = map(properties.get(component.getName()));
                Class<?> type = component.getType();
                if (requestEnumFields.containsKey(field)) {
                    assertThat(type).as(field).isEqualTo(String.class);
                    assertThat(refOf(property)).as(field).isEqualTo(SCHEMA_PREFIX + requestEnumFields.get(field));
                    continue;
                }
                if (type.isRecord() || type.isEnum()) {
                    String expected = SCHEMA_PREFIX + (type.isRecord() ? schemaOf(records, type) : schemaOf(enums, type));
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
                    Type element = ((ParameterizedType) component.getGenericType()).getActualTypeArguments()[0];
                    Map<String, Object> items = map(property.get("items"));
                    if (element == String.class) {
                        assertThat(items.get("type") != null || items.get("$ref") != null).as(field).isTrue();
                    } else if (element instanceof Class<?> elementType && elementType.isEnum()) {
                        assertThat(items.get("$ref")).as(field + " items")
                                .isEqualTo(SCHEMA_PREFIX + schemaOf(enums, elementType));
                    } else {
                        assertThat(items.get("$ref")).as(field + " items")
                                .isEqualTo(SCHEMA_PREFIX + schemaOf(records, (Class<?>) element));
                    }
                }
            }
        });
    }

    public void verifyEnums() {
        assertThat(enums).allSatisfy((schemaName, enumType) ->
                assertThat(strings(map(schemas().get(schemaName)).get("enum"))).as(schemaName)
                        .containsExactlyElementsOf(Arrays.stream(enumType.getEnumConstants())
                                .map(constant -> ((Enum<?>) constant).name()).toList()));
        assertThat(requestEnumFields.values()).allSatisfy(name -> assertThat(enums).containsKey(name));
    }

    public void verifyEverySchemaIsCovered() {
        List<String> covered = new ArrayList<>(responseRecords.keySet());
        covered.addAll(requestRecords.keySet());
        covered.addAll(enums.keySet());
        covered.addAll(otherSchemas);
        assertThat(schemas().keySet()).containsExactlyInAnyOrderElementsOf(covered);
    }

    /** 契约里提到的 OPS_ 错误码，就是 errorCodeSchema 里列的，也正好是实现会给出的那些。 */
    public void verifyErrorCodes(String errorCodeSchema, Set<String> produced) {
        Set<String> mentioned = new TreeSet<>();
        Matcher matcher = Pattern.compile("OPS_[A-Z_]+").matcher(text());
        while (matcher.find()) {
            mentioned.add(matcher.group());
        }
        assertThat(mentioned).containsExactlyInAnyOrderElementsOf(produced);
        assertThat(strings(map(schemas().get(errorCodeSchema)).get("enum")))
                .containsExactlyInAnyOrderElementsOf(produced);
    }

    // ---- 控制器一侧 ----

    public record Operation(String httpMethod, String path, Method method) {
        public String key() {
            return httpMethod + " " + path;
        }
    }

    public List<Operation> operations() {
        List<Operation> operations = new ArrayList<>();
        for (Class<?> controller : controllers) {
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping != null) {
                    String[] paths = mapping.path().length > 0 ? mapping.path() : mapping.value();
                    assertThat(paths).as(method.getName()).hasSize(1);
                    assertThat(mapping.method()).as(method.getName()).hasSize(1);
                    operations.add(new Operation(mapping.method()[0].name(), paths[0], method));
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

    private static Type returnedBody(Method method) {
        Type type = method.getGenericReturnType();
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == ResponseEntity.class) {
            return parameterized.getActualTypeArguments()[0];
        }
        return type;
    }

    private static List<String> fields(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
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

    private Map<String, Object> declared(Operation operation) {
        Map<String, Object> item = map(map(root().get("paths")).get(operation.path()));
        return item == null ? null : map(item.get(operation.httpMethod().toLowerCase()));
    }

    private Map<String, Object> resolve(Map<String, Object> parameter) {
        Object ref = parameter.get("$ref");
        if (ref == null) {
            return parameter;
        }
        String name = ((String) ref).substring("#/components/parameters/".length());
        return map(map(map(root().get("components")).get("parameters")).get(name));
    }

    private static String jsonSchemaRef(Map<String, Object> body) {
        Map<String, Object> content = map(body.get("content"));
        return (String) map(map(content.get("application/json")).get("schema")).get("$ref");
    }

    private static String refOf(Map<String, Object> property) {
        if (property.get("$ref") != null) {
            return (String) property.get("$ref");
        }
        return list(property.get("anyOf")).stream()
                .map(option -> (String) map(option).get("$ref"))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static List<String> types(Map<String, Object> property) {
        Object type = property.get("type");
        if (type instanceof List<?> values) {
            return values.stream().map(String.class::cast).toList();
        }
        return type == null ? List.of() : List.of((String) type);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    public static List<Object> list(Object value) {
        if (value == null) {
            return List.of();
        }
        return ((List<?>) value).stream().map(Object.class::cast).toList();
    }

    public static List<String> strings(Object value) {
        return list(value).stream().map(String.class::cast).toList();
    }
}
