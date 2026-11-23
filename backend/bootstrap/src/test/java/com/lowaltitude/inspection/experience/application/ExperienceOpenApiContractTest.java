package com.lowaltitude.inspection.experience.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.experience.application.VenueBookingException.Error;
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
 * 契约和实现逐项对照（1.1.0）：两个控制器的每个路由和方法都在契约里（目录五个公开，到馆预约七个要登录），
 * 参数、请求头、请求体、返回对象都指向对应的 schema；每个对象的字段与 schema 的 properties、required、类型一致，
 * 枚举取值一致，业务错误码与全局异常处理给出的一致。改了一边忘了另一边会立刻失败。
 */
class ExperienceOpenApiContractTest {

    private static final Path CONTRACT = Path.of("..", "..", "contracts", "openapi-experience.yaml");
    private static final String SCHEMA_PREFIX = "#/components/schemas/";

    /** 返回对象：字段全部必有，可以为空的用 null 表示。 */
    private static final Map<String, Class<? extends Record>> SCHEMA_RECORDS = Map.ofEntries(
            Map.entry("MediaAsset", ExperienceMediaView.class),
            Map.entry("ExperienceItem", ExperienceItemView.class),
            Map.entry("ExperienceItemPage", ExperienceItemPageView.class),
            Map.entry("VenueCard", VenueCardView.class),
            Map.entry("VenuePage", VenuePageView.class),
            Map.entry("VenueOperator", VenueOperatorView.class),
            Map.entry("VenueProject", VenueProjectView.class),
            Map.entry("VenueInfoItem", VenueInfoItemView.class),
            Map.entry("VenueDetail", VenueDetailView.class),
            Map.entry("VenueCheckInRule", VenueCheckInRuleView.class),
            Map.entry("VenueSession", VenueSessionView.class),
            Map.entry("VenueSessionList", VenueSessionListView.class),
            Map.entry("VenueDevice", VenueDeviceView.class),
            Map.entry("VenueDevicePage", VenueDevicePageView.class),
            Map.entry("VenueParticipant", VenueParticipantView.class),
            Map.entry("VenueParticipantList", VenueParticipantListView.class),
            Map.entry("VenueBookingVenue", BookingVenueView.class),
            Map.entry("VenueBookingProject", BookingProjectView.class),
            Map.entry("VenueBookingSession", BookingSessionView.class),
            Map.entry("VenueBookedParticipant", BookedParticipantView.class),
            Map.entry("VenueBookingPayment", VenueBookingPaymentView.class),
            Map.entry("VenueBookingRefund", VenueBookingRefundView.class),
            Map.entry("VenueBooking", VenueBookingView.class),
            Map.entry("VenueBookingPage", VenueBookingPageView.class),
            Map.entry("VenueCheckin", VenueCheckinView.class),
            Map.entry("ProductExperienceItem", ProductExperienceItemView.class),
            Map.entry("ProductExperienceVenue", ProductExperienceVenueView.class),
            Map.entry("ProductExperiences", ProductExperiencesView.class));

    /** 请求体：字段名与控制器的请求对象一致；哪些必填由服务的校验决定，这里逐个写明。 */
    private static final Map<String, Class<? extends Record>> REQUEST_RECORDS = Map.of(
            "VenueBookRequest", VenueBookingController.BookRequest.class,
            "VenueRescheduleRequest", VenueBookingController.RescheduleRequest.class,
            "VenueCheckinRequest", VenueBookingController.CheckinRequest.class);

    private static final Map<String, List<String>> REQUEST_REQUIRED = Map.of(
            "VenueBookRequest", List.of("sessionId", "participantIds", "expectedAmountMinor", "noticeAgreed"),
            "VenueRescheduleRequest", List.of("sessionId"),
            "VenueCheckinRequest", List.of("code"));

    private static final Map<String, Class<? extends Enum<?>>> SCHEMA_ENUMS = Map.ofEntries(
            Map.entry("ExperienceType", ExperienceType.class),
            Map.entry("VenueSessionStatus", VenueSessionStatus.class),
            Map.entry("VenueDeviceCategory", VenueDeviceCategory.class),
            Map.entry("VenueDeviceTrialMode", VenueDeviceTrialMode.class),
            Map.entry("VenueBookingStatus", VenueBookingStatus.class),
            Map.entry("VenueBookingBucket", VenueBookingBucket.class),
            Map.entry("VenuePaymentStatus", VenuePaymentStatus.class),
            Map.entry("VenueRefundStatus", VenueRefundStatus.class),
            Map.entry("VenueParticipantRelation", VenueParticipantRelation.class),
            Map.entry("VenueEligibilityReason", VenueEligibilityReason.class));

    @Test
    void contractIsVersionOnePointThreeAndDeclaresBearerJwt() {
        Map<String, Object> root = root();

        // 1.3.0：核销工作台（S3-3）的登记放行，路由和字段不变。
        assertThat(map(root.get("info")).get("version")).isEqualTo("1.3.0");
        assertThat(map(map(root.get("components")).get("securitySchemes")).get("bearerAuth"))
                .isEqualTo(Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT"));
        assertThat(root).doesNotContainKey("security");
    }

    @Test
    void theSixCatalogRoutesStayPublic() {
        List<Operation> catalog = operations(ExperienceCatalogController.class);

        assertThat(catalog).extracting(Operation::key).containsExactlyInAnyOrder(
                "GET /api/v1/experiences",
                "GET /api/v1/venues",
                "GET /api/v1/venues/{venueId}",
                "GET /api/v1/venues/{venueId}/sessions",
                "GET /api/v1/venues/{venueId}/devices",
                "GET /api/v1/experience/product-experiences");
        assertThat(catalog).allSatisfy(operation ->
                assertThat(declared(operation).get("security")).as(operation.key()).isEqualTo(List.of()));
    }

    @Test
    void everyBookingRouteIsDeclaredWithItsMethodAndNeedsALogin() {
        List<Operation> booking = operations(VenueBookingController.class);

        assertThat(booking).extracting(Operation::key).containsExactlyInAnyOrder(
                "GET /api/v1/venue-sessions/{sessionId}/participants",
                "POST /api/v1/venue-bookings",
                "GET /api/v1/venue-bookings",
                "GET /api/v1/venue-bookings/{bookingId}",
                "POST /api/v1/venue-bookings/{bookingId}/reschedule",
                "POST /api/v1/venue-bookings/{bookingId}/cancel",
                "POST /api/v1/venue-checkins");
        assertThat(booking).allSatisfy(operation -> {
            Map<String, Object> declared = declared(operation);
            assertThat(declared).as(operation.key()).isNotNull();
            assertThat(declared.get("security"))
                    .as(operation.key() + " security")
                    .isEqualTo(List.of(Map.of("bearerAuth", List.of())));
            assertThat(map(declared.get("responses")).keySet())
                    .as(operation.key() + " responses")
                    .contains("400", "401", "403");
        });
    }

    // R6-2：商品能在哪体验。商品 ID 和城市都必填；两个列表都有上限，和读服务的一致。
    @Test
    void productExperiencesNeedTheProductAndTheCityAndAreCapped() {
        Map<String, Object> declared = map(map(map(root().get("paths"))
                .get("/api/v1/experience/product-experiences")).get("get"));
        Map<String, Map<String, Object>> parameters = new LinkedHashMap<>();
        for (Object entry : list(declared.get("parameters"))) {
            Map<String, Object> parameter = resolve(map(entry));
            parameters.put((String) parameter.get("name"), parameter);
        }
        assertThat(parameters.keySet()).containsExactly("productId", "cityCode");
        assertThat(parameters.values()).allSatisfy(parameter -> {
            assertThat(parameter.get("in")).isEqualTo("query");
            assertThat(parameter.get("required")).isEqualTo(true);
        });
        assertThat(map(parameters.get("productId").get("schema"))).containsEntry("format", "uuid");
        assertThat(map(declared.get("responses")).keySet()).containsExactlyInAnyOrder("200", "400");
        Map<String, Object> properties = map(map(schemas().get("ProductExperiences")).get("properties"));
        assertThat(map(properties.get("experiences")).get("maxItems"))
                .isEqualTo(ExperienceCatalogReadService.MAX_PRODUCT_EXPERIENCES);
        assertThat(map(properties.get("venues")).get("maxItems"))
                .isEqualTo(ExperienceCatalogReadService.MAX_PRODUCT_VENUES);
    }

    @Test
    void contractDeclaresNoOperationTheControllersDoNotHave() {
        Set<String> implemented = new TreeSet<>();
        operations(ExperienceCatalogController.class).forEach(operation -> implemented.add(operation.key()));
        operations(VenueBookingController.class).forEach(operation -> implemented.add(operation.key()));
        Set<String> declared = new TreeSet<>();
        map(root().get("paths")).forEach((path, item) -> map(item).keySet()
                .forEach(method -> declared.add(method.toUpperCase() + " " + path)));

        assertThat(implemented).hasSize(13);
        assertThat(declared).containsExactlyElementsOf(implemented);
    }

    @Test
    void everyParameterOfEveryBookingRouteIsDeclaredInTheSamePlace() {
        assertThat(operations(VenueBookingController.class)).allSatisfy(operation -> {
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
                    implemented.add("query " + name(query.name(), query.value(), parameter));
                }
                if (path != null) {
                    String key = "path " + name(path.name(), path.value(), parameter);
                    implemented.add(key);
                    assertThat(declared.get(key)).as(operation.key() + " " + key).isNotNull();
                    assertThat(declared.get(key).get("required")).as(key).isEqualTo(true);
                }
                if (header != null) {
                    String key = "header " + name(header.name(), header.value(), parameter);
                    implemented.add(key);
                    assertThat(declared.get(key)).as(operation.key() + " " + key).isNotNull();
                    assertThat(declared.get(key).get("required")).as(key).isEqualTo(header.required());
                }
            }
            assertThat(declared.keySet())
                    .as(operation.key() + " parameters")
                    .containsExactlyInAnyOrderElementsOf(implemented);
        });
    }

    // 幂等键只在提交预约时要；If-Match 只在改期时要。返回预约的接口都带 ETag。
    @Test
    void onlyBookingTakesTheIdempotencyKeyAndOnlyReschedulingTakesIfMatch() {
        Map<String, Object> parameters = map(map(root().get("components")).get("parameters"));
        assertThat(map(parameters.get("IdempotencyKey"))).containsEntry("name", "Idempotency-Key")
                .containsEntry("in", "header").containsEntry("required", true);
        assertThat(map(parameters.get("IfMatch"))).containsEntry("name", "If-Match")
                .containsEntry("in", "header").containsEntry("required", true);
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Operation operation : operations(VenueBookingController.class)) {
            headers.put(operation.key(), list(declared(operation).get("parameters")).stream()
                    .map(entry -> resolve(map(entry)))
                    .filter(parameter -> "header".equals(parameter.get("in")))
                    .map(parameter -> (String) parameter.get("name"))
                    .toList());
        }
        assertThat(headers).containsEntry("POST /api/v1/venue-bookings", List.of("Idempotency-Key"))
                .containsEntry("POST /api/v1/venue-bookings/{bookingId}/reschedule", List.of("If-Match"));
        assertThat(headers.values().stream().filter(names -> !names.isEmpty())).hasSize(2);
        for (String[] response : new String[][] {
                {"/api/v1/venue-bookings", "post", "201"},
                {"/api/v1/venue-bookings", "post", "200"},
                {"/api/v1/venue-bookings/{bookingId}", "get", "200"},
                {"/api/v1/venue-bookings/{bookingId}/reschedule", "post", "200"},
                {"/api/v1/venue-bookings/{bookingId}/cancel", "post", "200"}}) {
            Map<String, Object> declared = map(map(map(map(map(root().get("paths")).get(response[0]))
                    .get(response[1])).get("responses")).get(response[2]));
            assertThat(map(declared.get("headers"))).as(String.join(" ", response)).containsKey("ETag");
        }
    }

    @Test
    void everyRequestBodyAndSuccessResponsePointsAtTheSchemaOfItsJavaType() {
        List<Operation> all = new ArrayList<>(operations(ExperienceCatalogController.class));
        all.addAll(operations(VenueBookingController.class));

        assertThat(all).allSatisfy(operation -> {
            Map<String, Object> declared = declared(operation);
            Parameter body = Arrays.stream(operation.method().getParameters())
                    .filter(parameter -> parameter.isAnnotationPresent(RequestBody.class))
                    .findFirst()
                    .orElse(null);
            if (body == null) {
                assertThat(declared).as(operation.key()).doesNotContainKey("requestBody");
            } else {
                Map<String, Object> requestBody = map(declared.get("requestBody"));
                assertThat(requestBody).as(operation.key() + " requestBody").isNotNull();
                assertThat(requestBody.get("required")).isEqualTo(body.getAnnotation(RequestBody.class).required());
                assertThat(jsonSchemaRef(requestBody))
                        .as(operation.key() + " requestBody schema")
                        .isEqualTo(SCHEMA_PREFIX + schemaOf(REQUEST_RECORDS, body.getType()));
            }
            Map<String, Object> responses = map(declared.get("responses"));
            List<String> success = responses.keySet().stream().filter(code -> code.startsWith("2")).toList();
            Class<?> returned = returnedBody(operation.method());
            assertThat(success).as(operation.key() + " success responses").isNotEmpty();
            assertThat(success).allSatisfy(code -> assertThat(jsonSchemaRef(map(responses.get(code))))
                    .as(operation.key() + " " + code)
                    .isEqualTo(SCHEMA_PREFIX + schemaOf(SCHEMA_RECORDS, returned)));
        });
        assertThat(successCodes("post", "/api/v1/venue-bookings")).containsExactlyInAnyOrder("201", "200");
    }

    @Test
    void everyResponseObjectHasExactlyTheFieldsTheContractRequires() {
        Map<String, Object> schemas = schemas();

        assertThat(SCHEMA_RECORDS).allSatisfy((schemaName, recordType) -> {
            Map<String, Object> schema = map(schemas.get(schemaName));
            List<String> fields = Arrays.stream(recordType.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(schema).as(schemaName).isNotNull();
            assertThat(schema.get("additionalProperties")).as(schemaName).isEqualTo(false);
            assertThat(map(schema.get("properties")).keySet())
                    .as(schemaName + " properties")
                    .containsExactlyInAnyOrderElementsOf(fields);
            assertThat(strings(schema.get("required")))
                    .as(schemaName + " required")
                    .containsExactlyInAnyOrderElementsOf(fields);
        });
    }

    @Test
    void everyRequestObjectHasTheControllersFieldsAndTheRequiredOnes() {
        Map<String, Object> schemas = schemas();

        assertThat(REQUEST_RECORDS).allSatisfy((schemaName, recordType) -> {
            Map<String, Object> schema = map(schemas.get(schemaName));
            List<String> fields = Arrays.stream(recordType.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();

            assertThat(schema.get("additionalProperties")).as(schemaName).isEqualTo(false);
            assertThat(map(schema.get("properties")).keySet())
                    .as(schemaName + " properties")
                    .containsExactlyInAnyOrderElementsOf(fields);
            assertThat(strings(schema.get("required")))
                    .as(schemaName + " required")
                    .containsExactlyInAnyOrderElementsOf(REQUEST_REQUIRED.get(schemaName));
        });
        assertThat(Arrays.stream(VenueBookingController.class.getDeclaredClasses())
                .filter(Class::isRecord)
                .filter(type -> !java.lang.reflect.Modifier.isPrivate(type.getModifiers())))
                .containsExactlyInAnyOrderElementsOf(REQUEST_RECORDS.values());
    }

    // 字段名对了还不够：每个字段的类型、格式和指向的 schema 也要和 Java 类型对得上，基本类型不能声明成可空。
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
                    Map<String, Object> items = map(property.get("items"));
                    if (element.isRecord()) {
                        assertThat(items.get("$ref")).as(field + " items")
                                .isEqualTo(SCHEMA_PREFIX + schemaOf(SCHEMA_RECORDS, element));
                    } else if (element == UUID.class) {
                        assertThat(items).as(field + " items").containsEntry("format", "uuid");
                    } else {
                        throw new AssertionError("no rule for the element type of " + field);
                    }
                }
            }
        });
    }

    @Test
    void enumValuesMatchTheContract() {
        Map<String, Object> schemas = schemas();

        assertThat(SCHEMA_ENUMS).allSatisfy((schemaName, enumType) ->
                assertThat(strings(map(schemas.get(schemaName)).get("enum")))
                        .as(schemaName)
                        .containsExactlyElementsOf(Arrays.stream(enumType.getEnumConstants())
                                .map(Enum::name)
                                .toList()));
    }

    // 预约的每一种业务拒绝：全局异常处理给出的状态码和错误码，契约里至少有一个接口在同一个状态码下写了它。
    @Test
    void everyBusinessErrorCodeIsDeclaredUnderTheStatusTheHandlerAnswers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Set<String> produced = new TreeSet<>();
        for (Error error : Error.values()) {
            ResponseEntity<ApiError> response = handler.handleVenueBooking(
                    new VenueBookingException(error), new MockHttpServletRequest());
            String code = response.getBody().code();
            String status = String.valueOf(response.getStatusCode().value());
            if (!code.startsWith("VENUE_")) {
                assertThat(code).as(error.name()).isIn("COMMON_NOT_FOUND", "COMMON_FORBIDDEN");
                continue;
            }
            produced.add(code);
            assertThat(operations(VenueBookingController.class).stream()
                    .map(operation -> map(map(declared(operation).get("responses")).get(status)))
                    .filter(declaredResponse -> declaredResponse != null && declaredResponse.get("description") != null)
                    .map(declaredResponse -> (String) declaredResponse.get("description")))
                    .as(code + " under " + status)
                    .anyMatch(description -> description.contains(code));
        }

        assertThat(strings(map(schemas().get("VenueErrorCode")).get("enum")))
                .containsExactlyInAnyOrderElementsOf(produced);
        Set<String> mentioned = new TreeSet<>();
        Matcher matcher = Pattern.compile("VENUE_[A-Z_]+").matcher(contractText());
        while (matcher.find()) {
            mentioned.add(matcher.group());
        }
        assertThat(mentioned).containsExactlyElementsOf(produced);
    }

    @Test
    void everySchemaInTheContractIsCoveredByThisTest() {
        List<String> covered = new ArrayList<>(SCHEMA_RECORDS.keySet());
        covered.addAll(REQUEST_RECORDS.keySet());
        covered.addAll(SCHEMA_ENUMS.keySet());
        covered.add("VenueErrorCode");
        covered.add("ApiError");

        assertThat(schemas().keySet()).containsExactlyInAnyOrderElementsOf(covered);
    }

    // ---- 控制器一侧 ----

    private record Operation(String httpMethod, String path, Method method) {
        String key() {
            return httpMethod + " " + path;
        }
    }

    private static List<Operation> operations(Class<?> controller) {
        List<Operation> operations = new ArrayList<>();
        for (Method method : controller.getDeclaredMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping != null) {
                assertThat(mapping.path()).as(method.getName()).hasSize(1);
                assertThat(mapping.method()).as(method.getName()).hasSize(1);
                operations.add(new Operation(mapping.method()[0].name(), mapping.path()[0], method));
            }
        }
        return operations;
    }

    /** 注解里的 name 和 value 是同一个属性的两种写法；都没写时用 Java 参数名。 */
    private static String name(String name, String value, Parameter parameter) {
        if (!name.isEmpty()) {
            return name;
        }
        return value.isEmpty() ? parameter.getName() : value;
    }

    /** 返回值的正文类型：ResponseEntity 取里面那一层。 */
    private static Class<?> returnedBody(Method method) {
        Type type = method.getGenericReturnType();
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == ResponseEntity.class) {
            return (Class<?>) parameterized.getActualTypeArguments()[0];
        }
        return method.getReturnType();
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
        if (type == Double.class) {
            return "number";
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

    /** 契约里这个路由、这个方法的操作；没有声明时为 null。 */
    private static Map<String, Object> declared(Operation operation) {
        Map<String, Object> item = map(map(root().get("paths")).get(operation.path()));
        return item == null ? null : map(item.get(operation.httpMethod().toLowerCase()));
    }

    private static List<String> successCodes(String httpMethod, String path) {
        Map<String, Object> responses = map(map(map(map(root().get("paths")).get(path)).get(httpMethod)).get("responses"));
        return responses.keySet().stream().filter(code -> code.startsWith("2")).toList();
    }

    /** 参数可以直接写，也可以引用 components.parameters 里的。 */
    private static Map<String, Object> resolve(Map<String, Object> parameter) {
        Object ref = parameter.get("$ref");
        if (ref == null) {
            return parameter;
        }
        String name = ((String) ref).substring("#/components/parameters/".length());
        return map(map(map(root().get("components")).get("parameters")).get(name));
    }

    private static String jsonSchemaRef(Map<String, Object> bodyOrResponse) {
        Map<String, Object> content = map(bodyOrResponse.get("content"));
        return (String) map(map(content.get("application/json")).get("schema")).get("$ref");
    }

    /** 属性指向的 schema：直接 $ref，或者 anyOf 里“某个 schema 或 null”。 */
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

    /** type 可以是一个名字，也可以是带 "null" 的列表。 */
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
