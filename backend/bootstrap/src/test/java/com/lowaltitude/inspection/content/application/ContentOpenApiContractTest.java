package com.lowaltitude.inspection.content.application;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.yaml.snakeyaml.Yaml;

/**
 * 契约 contracts/openapi-content.yaml 和实现逐项对照（H1）：三个公开路由、查询参数、返回对象的字段和类型、
 * 枚举、页大小上限。改了一边忘了另一边会立刻失败。
 */
class ContentOpenApiContractTest {

    private static final Path CONTRACT = Path.of("..", "..", "contracts", "openapi-content.yaml");
    private static final String SCHEMA_PREFIX = "#/components/schemas/";

    private static final Map<String, Class<? extends Record>> SCHEMA_RECORDS = Map.ofEntries(
            Map.entry("ContentMedia", ContentMediaView.class),
            Map.entry("ContentAuthor", ContentAuthorView.class),
            Map.entry("ContentArticleCard", ContentArticleCardView.class),
            Map.entry("ContentTopicCard", ContentTopicCardView.class),
            Map.entry("ContentFeed", ContentFeedView.class),
            Map.entry("ContentBlockItem", ContentBlockItemView.class),
            Map.entry("ContentBlock", ContentBlockView.class),
            Map.entry("ContentRef", ContentRefView.class),
            Map.entry("ContentArticle", ContentArticleView.class),
            Map.entry("ContentTopic", ContentTopicView.class));

    private static final Map<String, Class<? extends Enum<?>>> SCHEMA_ENUMS = Map.of(
            "ContentArticleKind", ContentArticleKind.class,
            "ContentAuthorKind", ContentAuthorKind.class,
            "ContentFeedChannel", ContentFeedChannel.class,
            "ContentBlockType", ContentBlockType.class,
            "ContentRefType", ContentRefType.class);

    @Test
    void contractIsVersionOneAndEveryRouteIsPublic() {
        Map<String, Object> root = root();

        assertThat(map(root.get("info")).get("version")).isEqualTo("1.0.0");
        assertThat(root).doesNotContainKey("security");
        assertThat(map(root.get("components"))).doesNotContainKey("securitySchemes");
        assertThat(operations()).extracting(Operation::key).containsExactlyInAnyOrder(
                "GET /api/v1/content/feed",
                "GET /api/v1/content/articles/{articleId}",
                "GET /api/v1/content/topics/{topicId}");
        assertThat(operations()).allSatisfy(operation -> {
            Map<String, Object> declared = declared(operation);
            assertThat(declared.get("security")).as(operation.key()).isEqualTo(List.of());
            assertThat(map(declared.get("responses")).keySet()).as(operation.key()).contains("200", "400");
            if (operation.path().contains("{")) {
                assertThat(map(declared.get("responses"))).as(operation.key()).containsKey("404");
            }
        });
        Set<String> declaredKeys = new TreeSet<>();
        map(root.get("paths")).forEach((path, item) -> map(item).keySet()
                .forEach(method -> declaredKeys.add(method.toUpperCase() + " " + path)));
        assertThat(declaredKeys).hasSize(3);
    }

    @Test
    void everyParameterIsDeclaredInTheSamePlace() {
        assertThat(operations()).allSatisfy(operation -> {
            Map<String, Map<String, Object>> declared = new LinkedHashMap<>();
            for (Object entry : list(declared(operation).get("parameters"))) {
                Map<String, Object> parameter = resolve(map(entry));
                declared.put(parameter.get("in") + " " + parameter.get("name"), parameter);
            }
            Set<String> implemented = new TreeSet<>();
            for (Parameter parameter : operation.method().getParameters()) {
                RequestParam query = parameter.getAnnotation(RequestParam.class);
                PathVariable path = parameter.getAnnotation(PathVariable.class);
                if (query != null) {
                    implemented.add("query " + parameter.getName());
                }
                if (path != null) {
                    String key = "path " + parameter.getName();
                    implemented.add(key);
                    assertThat(map(declared.get(key).get("schema"))).containsEntry("format", "uuid");
                }
            }
            assertThat(declared.keySet()).as(operation.key()).containsExactlyInAnyOrderElementsOf(implemented);
        });
        Map<String, Object> feed = declared(new Operation("GET", "/api/v1/content/feed", null));
        Map<String, Map<String, Object>> parameters = new LinkedHashMap<>();
        for (Object entry : list(feed.get("parameters"))) {
            Map<String, Object> parameter = resolve(map(entry));
            parameters.put((String) parameter.get("name"), parameter);
        }
        assertThat(map(parameters.get("channel").get("schema")).get("$ref")).isEqualTo(SCHEMA_PREFIX + "ContentFeedChannel");
        assertThat(map(parameters.get("size").get("schema")).get("maximum")).isEqualTo(ContentReadService.MAX_PAGE_SIZE);
        assertThat(parameters.get("cityCode").get("required")).isEqualTo(true);
    }

    @Test
    void everySuccessResponsePointsAtTheSchemaOfItsJavaType() {
        assertThat(operations()).allSatisfy(operation -> {
            Map<String, Object> ok = map(map(declared(operation).get("responses")).get("200"));
            String ref = (String) map(map(map(ok.get("content")).get("application/json")).get("schema")).get("$ref");
            assertThat(ref).as(operation.key())
                    .isEqualTo(SCHEMA_PREFIX + schemaOf(SCHEMA_RECORDS, operation.method().getReturnType()));
        });
    }

    @Test
    void everyResponseObjectHasExactlyTheFieldsOfItsRecordAllRequired() {
        Map<String, Object> schemas = schemas();

        assertThat(SCHEMA_RECORDS).allSatisfy((name, type) -> {
            Map<String, Object> schema = map(schemas.get(name));
            List<String> fields = Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
            assertThat(schema.get("additionalProperties")).as(name).isEqualTo(false);
            assertThat(map(schema.get("properties")).keySet()).as(name).containsExactlyInAnyOrderElementsOf(fields);
            assertThat(strings(schema.get("required"))).as(name).containsExactlyInAnyOrderElementsOf(fields);
        });
    }

    @Test
    void everyFieldIsDeclaredWithTheTypeOfItsJavaComponent() {
        Map<String, Object> schemas = schemas();

        assertThat(SCHEMA_RECORDS).allSatisfy((name, type) -> {
            Map<String, Object> properties = map(map(schemas.get(name)).get("properties"));
            for (RecordComponent component : type.getRecordComponents()) {
                String field = name + "." + component.getName();
                Map<String, Object> property = map(properties.get(component.getName()));
                Class<?> javaType = component.getType();
                if (javaType.isRecord() || javaType.isEnum()) {
                    assertThat(refOf(property)).as(field).isEqualTo(SCHEMA_PREFIX + (javaType.isRecord()
                            ? schemaOf(SCHEMA_RECORDS, javaType)
                            : schemaOf(SCHEMA_ENUMS, javaType)));
                    continue;
                }
                List<String> types = types(property);
                assertThat(types).as(field).contains(jsonType(javaType));
                if (javaType.isPrimitive()) {
                    assertThat(types).as(field + " is never null").doesNotContain("null");
                }
                if (javaType == UUID.class) {
                    assertThat(property.get("format")).as(field).isEqualTo("uuid");
                }
                if (javaType == LocalDate.class) {
                    assertThat(property.get("format")).as(field).isEqualTo("date");
                }
                if (javaType == OffsetDateTime.class) {
                    assertThat(property.get("format")).as(field).isEqualTo("date-time");
                }
                if (javaType == List.class) {
                    Class<?> element = (Class<?>) ((ParameterizedType) component.getGenericType())
                            .getActualTypeArguments()[0];
                    Map<String, Object> items = map(property.get("items"));
                    if (element.isRecord()) {
                        assertThat(items.get("$ref")).as(field)
                                .isEqualTo(SCHEMA_PREFIX + schemaOf(SCHEMA_RECORDS, element));
                    } else {
                        assertThat(element).as(field).isEqualTo(UUID.class);
                        assertThat(items).as(field).containsEntry("type", "string").containsEntry("format", "uuid");
                    }
                }
            }
        });
        // 只有可能为空的字段声明成可空。
        Map<String, Object> card = map(map(schemas.get("ContentArticleCard")).get("properties"));
        assertThat(types(map(card.get("cityCode")))).contains("null");
        assertThat(types(map(card.get("title")))).doesNotContain("null");
        assertThat(list(map(card.get("coverImage")).get("anyOf"))).contains(Map.of("type", "null"));
        Map<String, Object> author = map(map(schemas.get("ContentAuthor")).get("properties"));
        assertThat(types(map(author.get("organizationId")))).contains("null");
        Map<String, Object> topic = map(map(schemas.get("ContentTopic")).get("properties"));
        assertThat(types(map(topic.get("cityCode")))).doesNotContain("null");
    }

    // 枚举的取值和顺序：契约里写的就是 Java 给出的；关联对象的每一类都说明了到哪个接口读真实数据。
    @Test
    void enumsMatchTheJavaSide() {
        Map<String, Object> schemas = schemas();

        assertThat(SCHEMA_ENUMS).allSatisfy((name, type) -> assertThat(strings(map(schemas.get(name)).get("enum")))
                .as(name)
                .containsExactlyElementsOf(Arrays.stream(type.getEnumConstants()).map(Enum::name).toList()));
        String refs = (String) map(schemas.get("ContentRefType")).get("description");
        assertThat(refs).contains("/api/v1/inspection/services/{id}", "/api/v1/pro-services/{id}",
                "/api/v1/mall/products/{id}", "/api/v1/activities/{id}", "/api/v1/venues/{id}");
        assertThat(contractText()).contains("security: []").doesNotContain("bearerAuth");
    }

    @Test
    void everySchemaInTheContractIsCoveredByThisTest() {
        List<String> covered = new ArrayList<>(SCHEMA_RECORDS.keySet());
        covered.addAll(SCHEMA_ENUMS.keySet());
        covered.add("ApiError");

        assertThat(schemas().keySet()).containsExactlyInAnyOrderElementsOf(covered);
    }

    // ---- 控制器一侧 ----

    private record Operation(String httpMethod, String path, Method method) {
        String key() {
            return httpMethod + " " + path;
        }
    }

    private static List<Operation> operations() {
        List<Operation> operations = new ArrayList<>();
        for (Method method : ContentController.class.getDeclaredMethods()) {
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
        if (type == String.class || type == UUID.class || type == LocalDate.class || type == OffsetDateTime.class) {
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
