package com.lowaltitude.inspection.course.application;

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

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.yaml.snakeyaml.Yaml;

/**
 * 两份课程契约（openapi-course.yaml、openapi-ops-course.yaml）和控制器、视图记录逐项对照用的工具：
 * 路由、参数、请求体和返回体指向的 schema、每个对象的字段和类型、枚举。和租赁、直播的契约测试同一套规则。
 */
final class CourseContract {

    static final String SCHEMA_PREFIX = "#/components/schemas/";

    record Operation(String httpMethod, String path, Method method) {
        String key() {
            return httpMethod + " " + path;
        }
    }

    private final Path file;
    private final Map<String, Class<?>> records;
    private final Map<String, Class<? extends Enum<?>>> enums;
    private final List<Class<?>> controllers;
    private final Map<String, Object> root;

    CourseContract(String fileName, Map<String, Class<?>> records, Map<String, Class<? extends Enum<?>>> enums,
                   List<Class<?>> controllers) {
        this.file = Path.of("..", "..", "contracts", fileName);
        this.records = records;
        this.enums = enums;
        this.controllers = controllers;
        this.root = new Yaml().load(text());
    }

    String text() {
        try {
            return Files.readString(file);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    Map<String, Object> root() {
        return root;
    }

    Map<String, Object> schemas() {
        return map(map(root.get("components")).get("schemas"));
    }

    List<Operation> operations() {
        List<Operation> operations = new ArrayList<>();
        for (Class<?> controller : controllers) {
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

    Set<String> declaredKeys() {
        Set<String> keys = new TreeSet<>();
        map(root.get("paths")).forEach((path, item) -> map(item).keySet()
                .forEach(method -> keys.add(method.toUpperCase() + " " + path)));
        return keys;
    }

    Map<String, Object> declared(Operation operation) {
        Map<String, Object> item = map(map(root.get("paths")).get(operation.path()));
        return item == null ? null : map(item.get(operation.httpMethod().toLowerCase()));
    }

    /** 每个参数（查询、路径、请求头）都在契约里，位置和名字一致；路径参数是 uuid。 */
    void assertParameters() {
        assertThat(operations()).allSatisfy(operation -> {
            Map<String, Map<String, Object>> declared = new LinkedHashMap<>();
            for (Object entry : list(declared(operation).get("parameters"))) {
                Map<String, Object> parameter = resolveParameter(map(entry));
                declared.put(parameter.get("in") + " " + parameter.get("name"), parameter);
            }
            Set<String> implemented = new TreeSet<>();
            for (Parameter parameter : operation.method().getParameters()) {
                RequestParam query = parameter.getAnnotation(RequestParam.class);
                PathVariable path = parameter.getAnnotation(PathVariable.class);
                RequestHeader header = parameter.getAnnotation(RequestHeader.class);
                if (query != null) {
                    String key = "query " + name(query.name(), query.value(), parameter);
                    implemented.add(key);
                    assertThat(declared.get(key).get("required")).as(operation.key() + " " + key)
                            .isEqualTo(query.required() && query.defaultValue().startsWith("\n"));
                }
                if (path != null) {
                    String key = "path " + name(path.name(), path.value(), parameter);
                    implemented.add(key);
                    assertThat(map(declared.get(key).get("schema"))).containsEntry("format", "uuid");
                }
                if (header != null) {
                    String key = "header " + name(header.name(), header.value(), parameter);
                    implemented.add(key);
                    assertThat(declared.get(key).get("required")).as(key).isEqualTo(header.required());
                }
            }
            assertThat(declared.keySet()).as(operation.key()).containsExactlyInAnyOrderElementsOf(implemented);
        });
    }

    /** 请求体和成功响应指向 Java 类型对应的 schema；返回 List 的指向数组，元素是对应的 schema。 */
    void assertBodies(Map<String, Class<?>> requestRecords) {
        assertThat(operations()).allSatisfy(operation -> {
            Map<String, Object> declared = declared(operation);
            Parameter body = Arrays.stream(operation.method().getParameters())
                    .filter(parameter -> parameter.isAnnotationPresent(RequestBody.class))
                    .findFirst()
                    .orElse(null);
            if (body == null) {
                assertThat(declared).as(operation.key()).doesNotContainKey("requestBody");
            } else {
                Map<String, Object> requestBody = map(declared.get("requestBody"));
                assertThat(requestBody.get("required")).isEqualTo(body.getAnnotation(RequestBody.class).required());
                assertThat(jsonSchema(requestBody).get("$ref")).as(operation.key())
                        .isEqualTo(SCHEMA_PREFIX + schemaOf(requestRecords, body.getType()));
            }
            Map<String, Object> responses = map(declared.get("responses"));
            List<String> success = responses.keySet().stream().filter(code -> code.startsWith("2")).toList();
            assertThat(success).as(operation.key()).isNotEmpty();
            Type returned = returnedBody(operation.method());
            for (String code : success) {
                Map<String, Object> schema = jsonSchema(map(responses.get(code)));
                if (returned instanceof ParameterizedType list && list.getRawType() == List.class) {
                    assertThat(schema.get("type")).as(operation.key()).isEqualTo("array");
                    assertThat(map(schema.get("items")).get("$ref")).as(operation.key())
                            .isEqualTo(SCHEMA_PREFIX + schemaOf(records, (Class<?>) list.getActualTypeArguments()[0]));
                } else {
                    assertThat(schema.get("$ref")).as(operation.key() + " " + code)
                            .isEqualTo(SCHEMA_PREFIX + schemaOf(records, (Class<?>) returned));
                }
            }
        });
    }

    /** 对象的字段和契约一致：返回对象全部必有（可空用 null 表示），请求对象按给定的必填项。 */
    void assertFields(Map<String, Class<?>> all, Map<String, List<String>> requestRequired) {
        Map<String, Object> schemas = schemas();
        assertThat(all).allSatisfy((schemaName, type) -> {
            Map<String, Object> schema = map(schemas.get(schemaName));
            List<String> fields = fields(type);
            assertThat(schema).as(schemaName).isNotNull();
            assertThat(schema.get("additionalProperties")).as(schemaName).isEqualTo(false);
            assertThat(map(schema.get("properties")).keySet()).as(schemaName).containsExactlyInAnyOrderElementsOf(fields);
            List<String> required = requestRequired.getOrDefault(schemaName, fields);
            assertThat(strings(schema.get("required"))).as(schemaName).containsExactlyInAnyOrderElementsOf(required);
        });
    }

    /** 每个字段的类型和 Java 一致：对象和枚举用 $ref，基本类型不能为 null，UUID、时间有 format，列表元素对得上。 */
    void assertTypes(Map<String, Class<?>> all) {
        Map<String, Object> schemas = schemas();
        assertThat(all).allSatisfy((schemaName, type) -> {
            Map<String, Object> properties = map(map(schemas.get(schemaName)).get("properties"));
            for (RecordComponent component : type.getRecordComponents()) {
                String field = schemaName + "." + component.getName();
                Map<String, Object> property = map(properties.get(component.getName()));
                // The update body distinguishes an omitted simulation ID from an explicit JSON null.
                Class<?> javaType = type == OpsCourseController.CourseRequest.class && component.getName().equals("simulationItemId")
                        ? UUID.class : component.getType();
                if (javaType.isRecord() || javaType.isEnum()) {
                    String expected = SCHEMA_PREFIX + (javaType.isRecord() ? schemaOf(all, javaType)
                            : schemaOf(enums, javaType));
                    assertThat(refOf(property)).as(field).isEqualTo(expected);
                    continue;
                }
                if (javaType == String.class && refOf(property) != null) {
                    // 请求里的枚举按字符串收、由服务解析（不认识的回 400），契约里指向那个枚举。
                    String ref = refOf(property).substring(SCHEMA_PREFIX.length());
                    assertThat(enums).as(field).containsKey(ref);
                    continue;
                }
                List<String> declaredTypes = types(property);
                assertThat(declaredTypes).as(field + " type").contains(jsonType(javaType));
                if (javaType.isPrimitive()) {
                    assertThat(declaredTypes).as(field + " is never null").doesNotContain("null");
                }
                if (javaType == UUID.class) {
                    assertThat(property.get("format")).as(field).isEqualTo("uuid");
                }
                if (javaType == OffsetDateTime.class) {
                    assertThat(property.get("format")).as(field).isEqualTo("date-time");
                }
                if (javaType == List.class) {
                    Class<?> element = (Class<?>) ((ParameterizedType) component.getGenericType())
                            .getActualTypeArguments()[0];
                    Map<String, Object> items = map(property.get("items"));
                    if (element == String.class || element == UUID.class) {
                        assertThat(items.get("type")).as(field + " items").isEqualTo("string");
                        if (element == UUID.class) {
                            assertThat(items.get("format")).as(field + " items").isEqualTo("uuid");
                        }
                    } else if (element.isEnum()) {
                        assertThat(items.get("$ref")).as(field + " items").isEqualTo(SCHEMA_PREFIX + schemaOf(enums, element));
                    } else {
                        assertThat(items.get("$ref")).as(field + " items").isEqualTo(SCHEMA_PREFIX + schemaOf(all, element));
                    }
                }
            }
        });
    }

    void assertEnums() {
        Map<String, Object> schemas = schemas();
        assertThat(enums).allSatisfy((schemaName, type) ->
                assertThat(strings(map(schemas.get(schemaName)).get("enum"))).as(schemaName)
                        .containsExactlyElementsOf(Arrays.stream(type.getEnumConstants()).map(Enum::name).toList()));
    }

    /** 某个状态码下的描述（直接写的或引用 components.responses 的）。 */
    List<String> descriptionsUnder(String status) {
        List<String> out = new ArrayList<>();
        for (Operation operation : operations()) {
            Map<String, Object> response = map(map(declared(operation).get("responses")).get(status));
            if (response == null) {
                continue;
            }
            if (response.get("$ref") != null) {
                String name = ((String) response.get("$ref")).substring("#/components/responses/".length());
                response = map(map(map(root.get("components")).get("responses")).get(name));
            }
            if (response.get("description") != null) {
                out.add((String) response.get("description"));
            }
        }
        return out;
    }

    private Map<String, Object> resolveParameter(Map<String, Object> parameter) {
        Object ref = parameter.get("$ref");
        if (ref == null) {
            return parameter;
        }
        String name = ((String) ref).substring("#/components/parameters/".length());
        return map(map(map(root.get("components")).get("parameters")).get(name));
    }

    private static Map<String, Object> jsonSchema(Map<String, Object> bodyOrResponse) {
        Map<String, Object> content = map(bodyOrResponse.get("content"));
        return map(map(content.get("application/json")).get("schema"));
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

    static List<String> fields(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    static String schemaOf(Map<String, ? extends Class<?>> registered, Class<?> type) {
        return registered.entrySet().stream()
                .filter(entry -> entry.getValue() == type)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError(type.getName() + " has no schema in this test"));
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
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        return value == null ? List.of() : (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Object value) {
        return (List<String>) value;
    }
}
