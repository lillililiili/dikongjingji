package com.lowaltitude.inspection.rental.application;

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
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.rental.application.RentalException.Error;
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
 * 契约 contracts/openapi-rental.yaml 和实现逐项对照（F3）：两个控制器的每个路由都在契约里并且要登录；参数、请求头、
 * 请求体、返回对象指向对应的 schema；每个对象的字段、类型、枚举与 Java 一致；长度和数量上限与服务一致；
 * 业务错误码与全局异常处理给出的一致。改了一边忘了另一边会立刻失败。
 */
class RentalOpenApiContractTest {

    private static final Path CONTRACT = Path.of("..", "..", "contracts", "openapi-rental.yaml");
    private static final String SCHEMA_PREFIX = "#/components/schemas/";

    private static final Map<String, Class<? extends Record>> SCHEMA_RECORDS = Map.ofEntries(
            Map.entry("RentalInquiry", RentalInquiryView.class),
            Map.entry("RentalInquiryPage", RentalInquiryPageView.class),
            Map.entry("RentalLessee", RentalLesseeView.class),
            Map.entry("RentalMerchant", RentalMerchantView.class),
            Map.entry("RentalItem", RentalItemView.class),
            Map.entry("RentalPlace", RentalPlaceView.class),
            Map.entry("RentalFee", RentalFeeView.class),
            Map.entry("RentalQuote", RentalQuoteView.class),
            Map.entry("RentalOrderSummary", RentalOrderSummaryView.class),
            Map.entry("RentalConfirmation", RentalConfirmationView.class),
            Map.entry("RentalContract", RentalContractView.class),
            Map.entry("RentalPayment", RentalPaymentView.class),
            Map.entry("RentalRecord", RentalRecordView.class),
            Map.entry("RentalRefund", RentalRefundView.class),
            Map.entry("RentalOrder", RentalOrderView.class),
            Map.entry("RentalOrderPage", RentalOrderPageView.class));

    private static final Map<String, Class<? extends Record>> REQUEST_RECORDS = Map.of(
            "RentalInquiryRequest", RentalController.InquiryRequest.class,
            "RentalConfirmRequest", RentalController.ConfirmRequest.class,
            "RentalQuoteRequest", RentalMerchantController.QuoteRequest.class,
            "RentalFeeRequest", RentalMerchantController.FeeRequest.class,
            "RentalReasonRequest", RentalMerchantController.ReasonRequest.class,
            "RentalRecordRequest", RentalMerchantController.RecordRequest.class);

    private static final Map<String, List<String>> REQUEST_REQUIRED = Map.of(
            "RentalInquiryRequest", List.of("offerId", "quantity", "startDate", "days", "handoverMode", "returnMode",
                    "addressId"),
            "RentalConfirmRequest", List.of("quoteVersion", "agreed"),
            "RentalQuoteRequest", List.of("dailyRentMinor", "days", "quantity", "scheduleAvailable", "validUntil",
                    "terms"),
            "RentalFeeRequest", List.of("name", "amountMinor"),
            "RentalReasonRequest", List.of("reason"),
            "RentalRecordRequest", List.of("note"));

    private static final Map<String, Class<? extends Enum<?>>> SCHEMA_ENUMS = Map.of(
            "RentalInquiryStatus", RentalInquiryStatus.class,
            "RentalCloseReason", RentalCloseReason.class,
            "RentalOrderStatus", RentalOrderStatus.class,
            "RentalHandoverMode", RentalHandoverMode.class,
            "RentalReturnMode", RentalReturnMode.class,
            "RentalLesseeKind", RentalLesseeKind.class);

    private static final List<Class<?>> CONTROLLERS = List.of(RentalController.class, RentalMerchantController.class);

    @Test
    void contractIsVersionOneAndDeclaresBearerJwt() {
        Map<String, Object> root = root();

        assertThat(map(root.get("info")).get("version")).isEqualTo("1.1.0");
        assertThat(map(map(root.get("components")).get("securitySchemes")).get("bearerAuth"))
                .isEqualTo(Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT"));
        assertThat(root).doesNotContainKey("security");
    }

    @Test
    void everyRouteIsDeclaredWithItsMethodAndNeedsALogin() {
        List<Operation> operations = allOperations();

        assertThat(operations).extracting(Operation::key).containsExactlyInAnyOrder(
                "POST /api/v1/rental-inquiries",
                "GET /api/v1/rental-inquiries",
                "GET /api/v1/rental-inquiries/{inquiryId}",
                "POST /api/v1/rental-inquiries/{inquiryId}/close",
                "POST /api/v1/rental-inquiries/{inquiryId}/confirm",
                "GET /api/v1/rental-orders",
                "GET /api/v1/rental-orders/{orderId}",
                "POST /api/v1/rental-orders/{orderId}/handover-confirmation",
                "POST /api/v1/rental-orders/{orderId}/return-confirmation",
                "GET /api/v1/provider/rental-inquiries",
                "GET /api/v1/provider/rental-inquiries/{inquiryId}",
                "PUT /api/v1/provider/rental-inquiries/{inquiryId}/quote",
                "POST /api/v1/provider/rental-inquiries/{inquiryId}/decline",
                "GET /api/v1/provider/rental-orders",
                "GET /api/v1/provider/rental-orders/{orderId}",
                "POST /api/v1/provider/rental-orders/{orderId}/handover",
                "POST /api/v1/provider/rental-orders/{orderId}/return",
                "POST /api/v1/provider/rental-orders/{orderId}/cancel");
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
        assertThat(declaredKeys).hasSize(18);
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
                    implemented.add("query " + name(query.name(), query.value(), parameter));
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
        Map<String, Object> size = map(map(map(root().get("components")).get("parameters")).get("Size"));
        assertThat(map(size.get("schema")).get("maximum")).isEqualTo(RentalService.MAX_PAGE_SIZE);
    }

    // 幂等键只在提交询价时要；If-Match 只在确认合同时要。返回询价单的客户接口都带 ETag。
    @Test
    void onlySubmittingTakesTheIdempotencyKeyAndOnlyConfirmingTakesIfMatch() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Operation operation : allOperations()) {
            headers.put(operation.key(), list(declared(operation).get("parameters")).stream()
                    .map(entry -> resolve(map(entry)))
                    .filter(parameter -> "header".equals(parameter.get("in")))
                    .map(parameter -> (String) parameter.get("name"))
                    .toList());
        }
        assertThat(headers).containsEntry("POST /api/v1/rental-inquiries", List.of("Idempotency-Key"))
                .containsEntry("POST /api/v1/rental-inquiries/{inquiryId}/confirm", List.of("If-Match"));
        assertThat(headers.values().stream().filter(names -> !names.isEmpty())).hasSize(2);
        for (Operation operation : allOperations()) {
            Map<String, Object> responses = map(declared(operation).get("responses"));
            boolean withETag = operation.method().getDeclaringClass() == RentalController.class
                    && operation.method().getGenericReturnType() instanceof ParameterizedType;
            responses.keySet().stream().filter(code -> code.startsWith("2")).forEach(code -> {
                Map<String, Object> headersOf = map(map(responses.get(code)).get("headers"));
                if (withETag) {
                    assertThat(headersOf).as(operation.key() + " " + code).containsKey("ETag");
                } else {
                    assertThat(headersOf).as(operation.key() + " " + code).isNull();
                }
            });
        }
    }

    @Test
    void everyRequestBodyAndSuccessResponsePointsAtTheSchemaOfItsJavaType() {
        assertThat(allOperations()).allSatisfy(operation -> {
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
        assertThat(successCodes("post", "/api/v1/rental-inquiries")).containsExactlyInAnyOrder("201", "200");
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
            Arrays.stream(controller.getDeclaredClasses()).filter(Class::isRecord).forEach(requestTypes::add);
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
                if (type == LocalDate.class) {
                    assertThat(property.get("format")).as(field).isEqualTo("date");
                }
                if (type == List.class) {
                    Class<?> element = (Class<?>) ((ParameterizedType) component.getGenericType())
                            .getActualTypeArguments()[0];
                    Map<String, Object> items = map(property.get("items"));
                    Map<String, Class<? extends Record>> all = new LinkedHashMap<>(SCHEMA_RECORDS);
                    all.putAll(REQUEST_RECORDS);
                    assertThat(items.get("$ref")).as(field + " items")
                            .isEqualTo(SCHEMA_PREFIX + schemaOf(all, element));
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
        Map<String, Object> request = map(map(schemas.get("RentalInquiryRequest")).get("properties"));
        assertThat(strings(map(request.get("handoverMode")).get("enum"))).containsExactlyElementsOf(names(RentalHandoverMode.class));
        assertThat(strings(map(request.get("returnMode")).get("enum"))).containsExactlyElementsOf(names(RentalReturnMode.class));
        // 交接、归还方式的中文名写在契约的描述里。
        String handover = (String) map(schemas.get("RentalHandoverMode")).get("description");
        String giveBack = (String) map(schemas.get("RentalReturnMode")).get("description");
        Arrays.stream(RentalHandoverMode.values()).forEach(mode -> assertThat(handover).contains(mode.name() + " " + mode.label()));
        Arrays.stream(RentalReturnMode.values()).forEach(mode -> assertThat(giveBack).contains(mode.name() + " " + mode.label()));
    }

    @Test
    void limitsInTheContractAreTheOnesTheServiceEnforces() {
        Map<String, Object> schemas = schemas();
        Map<String, Object> inquiry = map(map(schemas.get("RentalInquiryRequest")).get("properties"));
        assertThat(map(inquiry.get("quantity")).get("maximum")).isEqualTo(RentalService.MAX_QUANTITY);
        assertThat(map(inquiry.get("days")).get("maximum")).isEqualTo(RentalService.MAX_DAYS);
        assertThat(map(inquiry.get("note")).get("maxLength")).isEqualTo(RentalService.MAX_NOTE);
        Map<String, Object> quote = map(map(schemas.get("RentalQuoteRequest")).get("properties"));
        assertThat(map(quote.get("fees")).get("maxItems")).isEqualTo(RentalService.MAX_FEES);
        assertThat(map(quote.get("terms")).get("maxLength")).isEqualTo(RentalService.MAX_TERMS);
        assertThat(((Number) map(quote.get("dailyRentMinor")).get("maximum")).longValue())
                .isEqualTo(RentalService.MAX_AMOUNT_MINOR);
        Map<String, Object> fee = map(map(schemas.get("RentalFeeRequest")).get("properties"));
        assertThat(map(fee.get("name")).get("maxLength")).isEqualTo(RentalService.MAX_FEE_NAME);
        assertThat(map(fee.get("note")).get("maxLength")).isEqualTo(RentalService.MAX_FEE_NOTE);
        assertThat(map(map(map(schemas.get("RentalReasonRequest")).get("properties")).get("reason")).get("maxLength"))
                .isEqualTo(RentalService.MAX_REASON);
        assertThat(map(map(map(schemas.get("RentalRecordRequest")).get("properties")).get("note")).get("maxLength"))
                .isEqualTo(RentalService.MAX_RECORD_NOTE);
        assertThat(contractText()).contains("30 分钟").contains("7 天");
        assertThat(RentalService.PAY_WINDOW).hasMinutes(30);
        assertThat(RentalService.QUOTE_WINDOW).hasDays(7);
        assertThat(RentalService.MAX_QUOTE_VALIDITY).hasDays(7);
    }

    @Test
    void everyBusinessErrorCodeIsDeclaredUnderTheStatusTheHandlerAnswers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Set<String> produced = new TreeSet<>();
        for (Error error : Error.values()) {
            ResponseEntity<ApiError> response = handler.handleRental(new RentalException(error),
                    new MockHttpServletRequest());
            String code = response.getBody().code();
            String status = String.valueOf(response.getStatusCode().value());
            if (!code.startsWith("RENTAL_")) {
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
        assertThat(strings(map(schemas().get("RentalErrorCode")).get("enum")))
                .containsExactlyInAnyOrderElementsOf(produced);
        Set<String> mentioned = new TreeSet<>();
        Matcher matcher = Pattern.compile("RENTAL_[A-Z_]+").matcher(contractText());
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
        covered.add("RentalErrorCode");
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
