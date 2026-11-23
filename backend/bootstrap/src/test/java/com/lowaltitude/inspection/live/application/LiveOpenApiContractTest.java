package com.lowaltitude.inspection.live.application;

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
import com.lowaltitude.inspection.live.application.LiveException.Error;
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
 * 契约 contracts/openapi-live.yaml 和实现逐项对照（M20）：三个控制器的每个路由都在契约里，公开的五个不要登录、其余要登录；
 * 参数、请求头、请求体、返回对象指向对应的 schema；每个对象的字段、类型、枚举与 Java 一致；长度和数量上限与服务一致；
 * 业务错误码与全局异常处理给出的一致。开发环境的模拟回调接口（LiveDevStreamController）不在契约里。
 */
class LiveOpenApiContractTest {

    private static final Path CONTRACT = Path.of("..", "..", "contracts", "openapi-live.yaml");
    private static final Path NOTIFICATION_CONTRACT = Path.of("..", "..", "contracts", "openapi-notification.yaml");
    private static final String SCHEMA_PREFIX = "#/components/schemas/";
    private static final String CALLBACK = "POST /api/v1/live/stream-callbacks";

    private static final Set<String> PUBLIC = Set.of(
            "GET /api/v1/live/rooms",
            "GET /api/v1/live/rooms/{roomId}",
            "GET /api/v1/live/rooms/{roomId}/comments",
            "POST /api/v1/live/rooms/{roomId}/views",
            CALLBACK);

    private static final Map<String, Class<? extends Record>> SCHEMA_RECORDS = Map.ofEntries(
            Map.entry("LiveHost", LiveHostView.class),
            Map.entry("LiveItem", LiveItemView.class),
            Map.entry("LiveRoomCard", LiveRoomCardView.class),
            Map.entry("LiveRoomPage", LiveRoomPageView.class),
            Map.entry("LiveRoom", LiveRoomView.class),
            Map.entry("LiveHostRoom", LiveHostRoomView.class),
            Map.entry("LiveHostRoomPage", LiveHostRoomPageView.class),
            Map.entry("LiveComment", LiveCommentView.class),
            Map.entry("LiveCommentPage", LiveCommentPageView.class),
            Map.entry("LiveViewerState", LiveViewerStateView.class),
            Map.entry("LiveViewCount", LiveViewCountView.class),
            Map.entry("LiveLikeResult", LiveLikeView.class),
            Map.entry("LiveFollowState", LiveFollowView.class),
            Map.entry("LiveReportResult", LiveReportView.class),
            Map.entry("LiveModeration", LiveModerationView.class));

    private static final Map<String, Class<? extends Record>> REQUEST_RECORDS = Map.of(
            "LiveViewRequest", LiveController.ViewRequest.class,
            "LiveCommentRequest", LiveController.CommentRequest.class,
            "LiveLikeRequest", LiveController.LikeRequest.class,
            "LiveRoomRequest", LiveHostController.RoomRequest.class,
            "LiveReplayRequest", LiveHostController.ReplayRequest.class,
            "LiveItemRequest", LiveHostController.ItemRequest.class,
            "LiveItemsRequest", LiveHostController.ItemsRequest.class,
            "LiveCurrentItemRequest", LiveHostController.CurrentItemRequest.class);

    private static final Map<String, List<String>> REQUEST_REQUIRED = Map.of(
            "LiveViewRequest", List.of("deviceId"),
            "LiveCommentRequest", List.of("text"),
            "LiveLikeRequest", List.of("count"),
            "LiveRoomRequest", List.of("title", "scheduledAt"),
            "LiveReplayRequest", List.of(),
            "LiveItemRequest", List.of("type", "refId", "title"),
            "LiveItemsRequest", List.of("items"),
            "LiveCurrentItemRequest", List.of());

    /** 请求里按字符串收、契约里引用枚举的字段。 */
    private static final Map<String, String> REQUEST_ENUM_FIELDS = Map.of("LiveItemRequest.type", "LiveItemType");

    private static final Map<String, Class<? extends Enum<?>>> SCHEMA_ENUMS = Map.of(
            "LiveRoomStatus", LiveRoomStatus.class,
            "LiveItemType", LiveItemType.class,
            "LiveCommentSide", LiveCommentSide.class);

    private static final List<Class<?>> CONTROLLERS = List.of(LiveController.class, LiveHostController.class,
            LiveStreamCallbackController.class);

    @Test
    void contractIsVersionOneAndDeclaresBearerJwt() {
        Map<String, Object> root = root();

        assertThat(map(root.get("info")).get("version")).isEqualTo("1.1.0");
        assertThat(map(map(root.get("components")).get("securitySchemes")).get("bearerAuth"))
                .isEqualTo(Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT"));
        assertThat(root).doesNotContainKey("security");
    }

    @Test
    void everyRouteIsDeclaredAndOnlyThePublicOnesWorkWithoutALogin() {
        List<Operation> operations = allOperations();

        assertThat(operations).extracting(Operation::key).containsExactlyInAnyOrder(
                "GET /api/v1/live/rooms",
                "GET /api/v1/live/rooms/{roomId}",
                "GET /api/v1/live/rooms/{roomId}/comments",
                "POST /api/v1/live/rooms/{roomId}/comments",
                "POST /api/v1/live/rooms/{roomId}/views",
                "GET /api/v1/live/rooms/{roomId}/me",
                "PUT /api/v1/live/rooms/{roomId}/reminder",
                "DELETE /api/v1/live/rooms/{roomId}/reminder",
                "POST /api/v1/live/rooms/{roomId}/likes",
                "POST /api/v1/live/rooms/{roomId}/comments/{commentId}/report",
                "PUT /api/v1/live/hosts/{organizationId}/follow",
                "DELETE /api/v1/live/hosts/{organizationId}/follow",
                CALLBACK,
                "GET /api/v1/live-host/rooms",
                "POST /api/v1/live-host/rooms",
                "GET /api/v1/live-host/rooms/{roomId}",
                "PUT /api/v1/live-host/rooms/{roomId}",
                "PUT /api/v1/live-host/rooms/{roomId}/replay",
                "PUT /api/v1/live-host/rooms/{roomId}/items",
                "PUT /api/v1/live-host/rooms/{roomId}/current-item",
                "POST /api/v1/live-host/rooms/{roomId}/comments",
                "POST /api/v1/live-host/rooms/{roomId}/comments/{commentId}/hide",
                "POST /api/v1/platform/live/rooms/{roomId}/comments/{commentId}/hide",
                "POST /api/v1/platform/live/rooms/{roomId}/end");
        assertThat(operations).allSatisfy(operation -> {
            Map<String, Object> declared = declared(operation);
            assertThat(declared).as(operation.key()).isNotNull();
            Set<String> responses = map(declared.get("responses")).keySet();
            if (PUBLIC.contains(operation.key())) {
                assertThat(declared.get("security")).as(operation.key())
                        .isIn(null, List.of());
                assertThat(responses).as(operation.key()).doesNotContain("403");
            } else {
                assertThat(declared.get("security")).as(operation.key())
                        .isEqualTo(List.of(Map.of("bearerAuth", List.of())));
                assertThat(responses).as(operation.key()).contains("400", "401", "403");
            }
            if (operation.path().contains("{")) {
                assertThat(responses).as(operation.key()).contains("404");
            }
        });
        Set<String> declaredKeys = new TreeSet<>();
        map(root().get("paths")).forEach((path, item) -> map(item).keySet()
                .forEach(method -> declaredKeys.add(method.toUpperCase() + " " + path)));
        assertThat(declaredKeys).hasSize(operations.size());
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
                RequestHeader header = parameter.getAnnotation(RequestHeader.class);
                if (query != null) {
                    String key = "query " + name(query.name(), query.value(), parameter);
                    implemented.add(key);
                    assertThat(declared.get(key).get("required")).as(key)
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
        Map<String, Object> parameters = map(map(root().get("components")).get("parameters"));
        assertThat(map(map(parameters.get("Size")).get("schema")))
                .containsEntry("maximum", LiveService.MAX_PAGE_SIZE)
                .containsEntry("default", LiveService.DEFAULT_PAGE_SIZE);
        assertThat(map(map(parameters.get("CommentSize")).get("schema")))
                .containsEntry("maximum", LiveService.MAX_COMMENT_PAGE)
                .containsEntry("default", LiveService.DEFAULT_COMMENT_PAGE);
    }

    // 幂等键只在建直播间和发弹幕时要；回调只带签名头；没有接口带 ETag 或 If-Match。
    @Test
    void onlyCreatingARoomAndSendingACommentTakeTheIdempotencyKey() {
        assertThat(allOperations()).allSatisfy(operation -> {
            List<String> names = list(declared(operation).get("parameters")).stream()
                    .map(entry -> resolve(map(entry)))
                    .filter(parameter -> "header".equals(parameter.get("in")))
                    .map(parameter -> (String) parameter.get("name"))
                    .toList();
            String key = operation.key();
            boolean keyed = key.equals("POST /api/v1/live-host/rooms") || key.endsWith("/{roomId}/comments")
                    && key.startsWith("POST");
            if (key.equals(CALLBACK)) {
                assertThat(names).containsExactly("X-Live-Timestamp", "X-Live-Signature");
            } else {
                assertThat(names).as(key).isEqualTo(keyed ? List.of("Idempotency-Key") : List.of());
            }
        });
        assertThat(contractText()).doesNotContain("ETag").doesNotContain("If-Match");
    }

    @Test
    void everyRequestBodyAndSuccessResponsePointsAtTheSchemaOfItsJavaType() {
        assertThat(allOperations()).allSatisfy(operation -> {
            if (operation.key().equals(CALLBACK)) {
                // 回调的请求体按原样交给端口验签，形状由服务商决定；成功是 204 没有响应体。
                assertThat(map(map(declared(operation).get("responses")).get("204"))).doesNotContainKey("content");
                return;
            }
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
                assertThat(jsonSchemaRef(requestBody)).as(operation.key())
                        .isEqualTo(SCHEMA_PREFIX + schemaOf(REQUEST_RECORDS, body.getType()));
            }
            Map<String, Object> responses = map(declared.get("responses"));
            List<String> success = responses.keySet().stream().filter(code -> code.startsWith("2")).toList();
            Class<?> returned = returnedBody(operation.method());
            assertThat(success).as(operation.key()).isNotEmpty();
            assertThat(success).allSatisfy(code -> assertThat(jsonSchemaRef(map(responses.get(code))))
                    .as(operation.key() + " " + code)
                    .isEqualTo(SCHEMA_PREFIX + schemaOf(SCHEMA_RECORDS, returned)));
        });
        for (String path : List.of("/api/v1/live/rooms/{roomId}/comments", "/api/v1/live-host/rooms",
                "/api/v1/live-host/rooms/{roomId}/comments")) {
            assertThat(successCodes("post", path)).as(path).containsExactlyInAnyOrder("201", "200");
        }
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
    void everyRequestObjectHasTheControllersFieldsAndTheRequiredOnes() {
        Map<String, Object> schemas = schemas();

        assertThat(REQUEST_RECORDS).allSatisfy((schemaName, recordType) -> {
            Map<String, Object> schema = map(schemas.get(schemaName));
            assertThat(schema.get("additionalProperties")).as(schemaName).isEqualTo(false);
            assertThat(map(schema.get("properties")).keySet()).as(schemaName)
                    .containsExactlyInAnyOrderElementsOf(fields(recordType));
            assertThat(strings(schema.get("required"))).as(schemaName)
                    .containsExactlyInAnyOrderElementsOf(REQUEST_REQUIRED.get(schemaName));
        });
        List<Class<?>> requestTypes = new ArrayList<>();
        for (Class<?> controller : CONTROLLERS) {
            Arrays.stream(controller.getDeclaredClasses()).filter(Class::isRecord)
                    .filter(type -> !java.lang.reflect.Modifier.isPrivate(type.getModifiers()))
                    .forEach(requestTypes::add);
        }
        assertThat(requestTypes).containsExactlyInAnyOrderElementsOf(REQUEST_RECORDS.values());
    }

    @Test
    void everyFieldIsDeclaredWithTheTypeOfItsJavaComponent() {
        Map<String, Object> schemas = schemas();
        Map<String, Class<? extends Record>> records = new LinkedHashMap<>(SCHEMA_RECORDS);
        records.putAll(REQUEST_RECORDS);

        assertThat(records).allSatisfy((schemaName, recordType) -> {
            Map<String, Object> properties = map(map(schemas.get(schemaName)).get("properties"));
            for (RecordComponent component : recordType.getRecordComponents()) {
                String field = schemaName + "." + component.getName();
                Map<String, Object> property = map(properties.get(component.getName()));
                Class<?> type = component.getType();
                if (REQUEST_ENUM_FIELDS.containsKey(field)) {
                    assertThat(type).as(field).isEqualTo(String.class);
                    assertThat(refOf(property)).as(field).isEqualTo(SCHEMA_PREFIX + REQUEST_ENUM_FIELDS.get(field));
                    continue;
                }
                if (type.isRecord() || type.isEnum()) {
                    String expected = SCHEMA_PREFIX + (type.isRecord()
                            ? schemaOf(records, type)
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
                    Map<String, Object> items = map(property.get("items"));
                    if (element == String.class) {
                        assertThat(items).as(field + " items").containsEntry("type", "string");
                    } else if (element == Long.class) {
                        assertThat(items).as(field + " items").containsEntry("type", "integer");
                    } else {
                        assertThat(items.get("$ref")).as(field + " items")
                                .isEqualTo(SCHEMA_PREFIX + schemaOf(records, element));
                    }
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
        // 站内信契约 1.4.0 起认得直播提醒和直播间（之后的版本只往后加）。
        assertThat(read(NOTIFICATION_CONTRACT))
                .containsPattern("version: 1\\.([4-9]|\\d{2,})\\.\\d+")
                .contains("ACTIVITY_REMINDER, LIVE_REMINDER")
                .contains("VENUE_BOOKING, LIVE_ROOM")
                .contains("LIVE_REMINDER it is LIVE_STARTED");
    }

    @Test
    void limitsInTheContractAreTheOnesTheServiceEnforces() {
        Map<String, Object> schemas = schemas();
        assertThat(property(schemas, "LiveCommentRequest", "text").get("maxLength")).isEqualTo(LiveService.MAX_COMMENT);
        assertThat(property(schemas, "LiveComment", "text").get("maxLength")).isEqualTo(LiveService.MAX_COMMENT);
        assertThat(property(schemas, "LiveLikeRequest", "count").get("maximum"))
                .isEqualTo(LiveService.MAX_LIKES_PER_WINDOW);
        assertThat(property(schemas, "LiveRoomRequest", "title").get("maxLength")).isEqualTo(LiveService.MAX_TITLE);
        assertThat(property(schemas, "LiveRoomRequest", "subtitle").get("maxLength")).isEqualTo(LiveService.MAX_SUBTITLE);
        assertThat(property(schemas, "LiveRoomRequest", "speakerName").get("maxLength"))
                .isEqualTo(LiveService.MAX_SPEAKER_NAME);
        assertThat(property(schemas, "LiveRoomRequest", "speakerTitle").get("maxLength"))
                .isEqualTo(LiveService.MAX_SPEAKER_TITLE);
        assertThat(property(schemas, "LiveRoomRequest", "topics").get("maxItems")).isEqualTo(LiveService.MAX_TOPICS);
        assertThat(map(property(schemas, "LiveRoomRequest", "topics").get("items")).get("maxLength"))
                .isEqualTo(LiveService.MAX_TOPIC);
        assertThat(property(schemas, "LiveRoomRequest", "coverUrl").get("maxLength")).isEqualTo(LiveService.MAX_URL);
        assertThat(property(schemas, "LiveReplayRequest", "replayUrl").get("maxLength")).isEqualTo(LiveService.MAX_URL);
        assertThat(property(schemas, "LiveReplayRequest", "replayPlatformName").get("maxLength"))
                .isEqualTo(LiveService.MAX_PLATFORM_NAME);
        assertThat(property(schemas, "LiveItemsRequest", "items").get("maxItems")).isEqualTo(LiveService.MAX_ITEMS);
        assertThat(property(schemas, "LiveItemRequest", "title").get("maxLength")).isEqualTo(LiveService.MAX_ITEM_TITLE);
        assertThat(contractText()).contains("1–50 字").contains("每 10 秒最多 30 次").contains("至少隔 3 秒")
                .contains("最近 100 条").contains("90 天以内").contains("过了一天还没开播");
        assertThat(LiveService.COMMENT_INTERVAL).hasSeconds(3);
        assertThat(LiveService.LIKE_WINDOW_SECONDS).isEqualTo(10);
        assertThat(LiveService.HIDDEN_WINDOW).isEqualTo(100);
        assertThat(LiveService.MAX_AHEAD).hasDays(90);
        assertThat(LiveService.STALE_SCHEDULE).hasDays(1);
    }

    @Test
    void everyBusinessErrorCodeIsDeclaredUnderTheStatusTheHandlerAnswers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Set<String> produced = new TreeSet<>();
        for (Error error : Error.values()) {
            ResponseEntity<ApiError> response = handler.handleLive(new LiveException(error),
                    new MockHttpServletRequest());
            String code = response.getBody().code();
            String status = String.valueOf(response.getStatusCode().value());
            if (!code.startsWith("LIVE_")) {
                assertThat(code).as(error.name()).isIn("COMMON_NOT_FOUND", "COMMON_FORBIDDEN");
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
        assertThat(strings(map(schemas().get("LiveErrorCode")).get("enum")))
                .containsExactlyInAnyOrderElementsOf(produced);
        Set<String> mentioned = new TreeSet<>();
        Matcher matcher = Pattern.compile("LIVE_[A-Z_]+").matcher(contractText());
        while (matcher.find()) {
            mentioned.add(matcher.group());
        }
        // 契约里出现的 LIVE_* 只有错误码和站内信的分类、目标、事件码（LIVE_HOST 是角色）。
        mentioned.removeAll(Set.of("LIVE_REMINDER", "LIVE_ROOM", "LIVE_HOST"));
        assertThat(mentioned).containsExactlyElementsOf(produced);
    }

    @Test
    void everySchemaInTheContractIsCoveredByThisTest() {
        List<String> covered = new ArrayList<>(SCHEMA_RECORDS.keySet());
        covered.addAll(REQUEST_RECORDS.keySet());
        covered.addAll(SCHEMA_ENUMS.keySet());
        covered.add("LiveErrorCode");
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

    private static Map<String, Object> property(Map<String, Object> schemas, String schema, String field) {
        return map(map(map(schemas.get(schema)).get("properties")).get(field));
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
