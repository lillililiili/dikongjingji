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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.yaml.snakeyaml.Yaml;

/**
 * 契约 contracts/openapi-content.yaml 和实现逐项对照（H1，1.2.0 起含视频）：路由和谁能调、查询参数、返回对象的字段和类型、
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
            Map.entry("ContentBlockImage", ContentBlockImageView.class),
            Map.entry("ContentTopicMerchantItem", ContentTopicMerchantItemView.class),
            Map.entry("ContentRef", ContentRefView.class),
            Map.entry("ContentArticle", ContentArticleView.class),
            Map.entry("ContentTopic", ContentTopicView.class),
            Map.entry("ContentRelated", ContentRelatedView.class),
            Map.entry("ContentVideoSummary", ContentVideoSummaryView.class),
            Map.entry("ContentVideo", ContentVideoView.class),
            Map.entry("ContentVideoPage", ContentVideoPageView.class),
            Map.entry("ContentVideoLike", ContentVideoLikeView.class),
            Map.entry("ContentFollow", ContentFollowView.class),
            Map.entry("ContentComment", ContentCommentView.class),
            Map.entry("ContentCommentPage", ContentCommentPageView.class),
            Map.entry("ContentCommentReport", ContentCommentReportView.class),
            Map.entry("ContentCommentHidden", ContentCommentHiddenView.class));

    /** 请求体：字段名和必填与 Java 一致（原因在 Java 里按文字收，由服务按枚举校验）。 */
    private static final Map<String, Class<? extends Record>> REQUEST_RECORDS = Map.of(
            "ContentCommentRequest", ContentVideoController.CommentRequest.class,
            "ContentCommentReportRequest", ContentVideoController.ReportRequest.class);

    /** 1.2.0 起不登录也能调的读取（带了令牌按登录的人给“我点过赞”等）。 */
    private static final Set<String> OPTIONAL_LOGIN = Set.of(
            "GET /api/v1/content/videos",
            "GET /api/v1/content/videos/{videoId}",
            "GET /api/v1/content/videos/{videoId}/comments");

    private static final Map<String, Class<? extends Enum<?>>> SCHEMA_ENUMS = Map.of(
            "ContentArticleKind", ContentArticleKind.class,
            "ContentAuthorKind", ContentAuthorKind.class,
            "ContentFeedChannel", ContentFeedChannel.class,
            "ContentBlockType", ContentBlockType.class,
            "ContentRefType", ContentRefType.class,
            "ContentVideoOrientation", ContentVideoOrientation.class,
            "ContentVideoFeed", ContentVideoFeed.class,
            "ContentCommentReportReason", ContentCommentReportReason.class);

    @Test
    void contractIsVersionOneTwoAndEveryRouteSaysWhoMayCallIt() {
        Map<String, Object> root = root();

        assertThat(map(root.get("info")).get("version")).isEqualTo("1.3.1");
        assertThat(root).doesNotContainKey("security");
        assertThat(map(map(map(root.get("components")).get("securitySchemes")).get("bearerAuth")))
                .containsEntry("type", "http").containsEntry("scheme", "bearer");
        assertThat(operations()).extracting(Operation::key).containsExactlyInAnyOrder(
                "GET /api/v1/content/feed",
                "GET /api/v1/content/articles/{articleId}",
                "GET /api/v1/content/topics/{topicId}",
                "GET /api/v1/content/related",
                "GET /api/v1/content/videos",
                "GET /api/v1/content/videos/{videoId}",
                "PUT /api/v1/content/videos/{videoId}/like",
                "DELETE /api/v1/content/videos/{videoId}/like",
                "GET /api/v1/content/videos/{videoId}/comments",
                "POST /api/v1/content/videos/{videoId}/comments",
                "POST /api/v1/content/comments/{commentId}/reports",
                "POST /api/v1/content/comments/{commentId}/hide",
                "PUT /api/v1/content/follows/{organizationId}",
                "DELETE /api/v1/content/follows/{organizationId}");
        assertThat(operations()).allSatisfy(operation -> {
            Map<String, Object> declared = declared(operation);
            Object security = declared.get("security");
            if (operation.method().getDeclaringClass() == ContentController.class) {
                assertThat(security).as(operation.key()).isEqualTo(List.of());
            } else if (OPTIONAL_LOGIN.contains(operation.key())) {
                assertThat(security).as(operation.key()).isEqualTo(List.of(Map.of(), Map.of("bearerAuth", List.of())));
            } else {
                assertThat(security).as(operation.key()).isEqualTo(List.of(Map.of("bearerAuth", List.of())));
                assertThat(map(declared.get("responses"))).as(operation.key()).containsKey("401");
            }
            assertThat(map(declared.get("responses")).keySet()).as(operation.key()).contains("400");
            assertThat(map(declared.get("responses")).keySet()).as(operation.key()).containsAnyOf("200", "201");
            // 取消关注不看机构是否存在（没关注也是 200），没有 404。
            if (operation.path().contains("{") && !operation.key().equals("DELETE /api/v1/content/follows/{organizationId}")) {
                assertThat(map(declared.get("responses"))).as(operation.key()).containsKey("404");
            }
        });
        Set<String> declaredKeys = new TreeSet<>();
        map(root.get("paths")).forEach((path, item) -> map(item).keySet()
                .forEach(method -> declaredKeys.add(method.toUpperCase() + " " + path)));
        assertThat(declaredKeys).hasSize(14);
    }

    // 1.2.0：视频流的条数上限、评论的长度上限、发评论的业务错误码。
    @Test
    void videoLimitsAndErrorsMatchTheService() {
        Map<String, Object> stream = declared(new Operation("GET", "/api/v1/content/videos", null));
        Map<String, Map<String, Object>> parameters = new LinkedHashMap<>();
        for (Object entry : list(stream.get("parameters"))) {
            Map<String, Object> parameter = resolve(map(entry));
            parameters.put((String) parameter.get("name"), parameter);
        }
        assertThat(map(parameters.get("size").get("schema")))
                .containsEntry("maximum", ContentVideoService.MAX_STREAM_SIZE)
                .containsEntry("default", ContentVideoService.DEFAULT_STREAM_SIZE);
        assertThat(map(parameters.get("feed").get("schema")).get("$ref")).isEqualTo(SCHEMA_PREFIX + "ContentVideoFeed");
        assertThat(map(parameters.get("cursor").get("schema"))).containsEntry("maxLength",
                ContentVideoService.MAX_CURSOR_LENGTH);
        assertThat(map(map(map(schemas().get("ContentVideoPage")).get("properties")).get("items")).get("maxItems"))
                .isEqualTo(ContentVideoService.MAX_STREAM_SIZE);
        assertThat(map(map(map(schemas().get("ContentCommentRequest")).get("properties")).get("text")))
                .containsEntry("maxLength", ContentVideoService.MAX_COMMENT_LENGTH);
        Map<String, Object> comment = map(declared(
                new Operation("POST", "/api/v1/content/videos/{videoId}/comments", null)).get("responses"));
        assertThat(comment).containsKeys("201", "200", "409", "422", "429");
        assertThat((String) map(comment.get("409")).get("description")).startsWith("CONTENT_IDEMPOTENCY_KEY_REUSED");
        assertThat((String) map(comment.get("422")).get("description")).startsWith("CONTENT_COMMENT_BLOCKED");
        assertThat((String) map(comment.get("429")).get("description")).startsWith("CONTENT_COMMENT_TOO_FREQUENT");
        assertThat(map(declared(new Operation("POST", "/api/v1/content/comments/{commentId}/hide", null))
                .get("responses"))).containsKey("403");
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
                RequestHeader header = parameter.getAnnotation(RequestHeader.class);
                if (query != null) {
                    implemented.add("query " + parameter.getName());
                }
                if (header != null) {
                    implemented.add("header " + header.value());
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

        // R6-3 相关内容：类型、ID、城市必填，limit 可选，上限和默认值与读服务一致。
        Map<String, Object> related = declared(new Operation("GET", "/api/v1/content/related", null));
        Map<String, Map<String, Object>> relatedParameters = new LinkedHashMap<>();
        for (Object entry : list(related.get("parameters"))) {
            Map<String, Object> parameter = resolve(map(entry));
            relatedParameters.put((String) parameter.get("name"), parameter);
        }
        assertThat(relatedParameters.keySet()).containsExactly("refType", "refId", "cityCode", "limit");
        assertThat(map(relatedParameters.get("refType").get("schema")).get("$ref"))
                .isEqualTo(SCHEMA_PREFIX + "ContentRefType");
        assertThat(map(relatedParameters.get("refId").get("schema"))).containsEntry("format", "uuid");
        for (String required : List.of("refType", "refId", "cityCode")) {
            assertThat(relatedParameters.get(required).get("required")).as(required).isEqualTo(true);
        }
        assertThat(relatedParameters.get("limit").get("required")).isEqualTo(false);
        assertThat(map(relatedParameters.get("limit").get("schema")))
                .containsEntry("maximum", ContentReadService.MAX_RELATED_LIMIT)
                .containsEntry("default", ContentReadService.DEFAULT_RELATED_LIMIT);
        assertThat(map(map(map(schemas().get("ContentRelated")).get("properties")).get("items")).get("maxItems"))
                .isEqualTo(ContentReadService.MAX_RELATED_LIMIT);
    }

    @Test
    void everySuccessResponsePointsAtTheSchemaOfItsJavaType() {
        assertThat(operations()).allSatisfy(operation -> {
            Class<?> body = operation.method().getReturnType();
            List<String> codes = List.of("200");
            if (body == ResponseEntity.class) {
                // 201 新建、200 重放：两个都是同一个形状。
                body = (Class<?>) ((ParameterizedType) operation.method().getGenericReturnType())
                        .getActualTypeArguments()[0];
                codes = List.of("201", "200");
            }
            for (String code : codes) {
                Map<String, Object> ok = map(map(declared(operation).get("responses")).get(code));
                assertThat(ok).as(operation.key() + " " + code).isNotNull();
                String ref = (String) map(map(map(ok.get("content")).get("application/json")).get("schema")).get("$ref");
                assertThat(ref).as(operation.key() + " " + code)
                        .isEqualTo(SCHEMA_PREFIX + schemaOf(SCHEMA_RECORDS, body));
            }
            // 有请求体的，请求体的形状和 Java 的记录一致。
            for (Parameter parameter : operation.method().getParameters()) {
                if (parameter.getAnnotation(RequestBody.class) != null) {
                    String ref = (String) map(map(map(map(declared(operation).get("requestBody")).get("content"))
                            .get("application/json")).get("schema")).get("$ref");
                    assertThat(ref).as(operation.key())
                            .isEqualTo(SCHEMA_PREFIX + schemaOf(REQUEST_RECORDS, parameter.getType()));
                }
            }
        });
    }

    @Test
    void requestBodiesHaveExactlyTheFieldsOfTheirRecords() {
        Map<String, Object> schemas = schemas();

        assertThat(REQUEST_RECORDS).allSatisfy((name, type) -> {
            Map<String, Object> schema = map(schemas.get(name));
            List<String> fields = Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
            assertThat(schema.get("additionalProperties")).as(name).isEqualTo(false);
            assertThat(map(schema.get("properties")).keySet()).as(name).containsExactlyInAnyOrderElementsOf(fields);
            assertThat(strings(schema.get("required"))).as(name).containsExactlyInAnyOrderElementsOf(fields);
        });
        assertThat(map(map(map(schemas.get("ContentCommentReportRequest")).get("properties")).get("reason")).get("$ref"))
                .isEqualTo(SCHEMA_PREFIX + "ContentCommentReportReason");
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
                    } else if (element == String.class) {
                        assertThat(items).as(field).containsEntry("type", "string");
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
        assertThat(contractText()).contains("security: []");
    }

    @Test
    void everySchemaInTheContractIsCoveredByThisTest() {
        List<String> covered = new ArrayList<>(SCHEMA_RECORDS.keySet());
        covered.addAll(REQUEST_RECORDS.keySet());
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
        for (Class<?> controller : List.of(ContentController.class, ContentVideoController.class)) {
            for (Method method : controller.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping != null) {
                    operations.add(new Operation(mapping.method()[0].name(), mapping.path()[0], method));
                }
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
