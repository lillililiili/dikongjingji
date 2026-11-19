package com.lowaltitude.inspection.mall.application;

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
import com.lowaltitude.inspection.mall.application.MallInquiryException.Error;
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
 * 契约 contracts/openapi-mall-inquiry.yaml 和实现逐项对照（F4）：两个控制器的每个路由都在契约里并且要登录；参数、请求头、
 * 请求体、返回对象指向对应的 schema；每个对象的字段、类型、枚举与 Java 一致；长度和数量上限与服务一致；
 * 业务错误码与全局异常处理给出的一致。改了一边忘了另一边会立刻失败。
 */
class MallInquiryOpenApiContractTest {

    private static final Path CONTRACT = Path.of("..", "..", "contracts", "openapi-mall-inquiry.yaml");
    private static final Path CART_CONTRACT = Path.of("..", "..", "contracts", "openapi-mall-cart.yaml");
    private static final Path TRADE_CONTRACT = Path.of("..", "..", "contracts", "openapi-trade.yaml");
    private static final Set<String> CHECKOUT_ONLY = Set.of("MALL_INQUIRY_QUOTE_NOT_CHOSEN");
    private static final String SCHEMA_PREFIX = "#/components/schemas/";

    private static final Map<String, Class<? extends Record>> SCHEMA_RECORDS = Map.ofEntries(
            Map.entry("MallMedia", MallMediaView.class),
            Map.entry("MallMerchant", MallMerchantView.class),
            Map.entry("MallInquiryBuyer", MallInquiryBuyerView.class),
            Map.entry("MallInquiryProduct", MallInquiryProductView.class),
            Map.entry("MallInquiryMerchant", MallInquiryMerchantView.class),
            Map.entry("MallInquiryChosen", MallInquiryChosenView.class),
            Map.entry("MallInquiry", MallInquiryView.class),
            Map.entry("MallInquiryPage", MallInquiryPageView.class),
            Map.entry("MallInquiryQuote", MallInquiryQuoteView.class),
            Map.entry("MallInquiryDetail", MallInquiryDetailView.class),
            Map.entry("MallInquiryOffer", MallInquiryOfferView.class),
            Map.entry("MallInquiryOfferList", MallInquiryOfferListView.class),
            Map.entry("MallMerchantQuote", MallMerchantQuoteView.class),
            Map.entry("MallMerchantInquiry", MallMerchantInquiryView.class),
            Map.entry("MallMerchantInquiryPage", MallMerchantInquiryPageView.class));

    private static final Map<String, Class<? extends Record>> REQUEST_RECORDS = Map.of(
            "MallInquiryRequest", MallInquiryController.InquiryRequest.class,
            "MallInquiryChoiceRequest", MallInquiryController.ChoiceRequest.class,
            "MallInquiryQuoteRequest", MallInquiryMerchantController.QuoteRequest.class,
            "MallInquiryDeclineRequest", MallInquiryMerchantController.DeclineRequest.class);

    private static final Map<String, List<String>> REQUEST_REQUIRED = Map.of(
            "MallInquiryRequest", List.of("productId", "requirement", "quantity", "cityCode", "offerIds"),
            "MallInquiryChoiceRequest", List.of("quoteId", "quoteVersion"),
            "MallInquiryQuoteRequest", List.of("unitAmountMinor", "model", "leadDays", "validUntil"),
            "MallInquiryDeclineRequest", List.of("reason"));

    private static final Map<String, Class<? extends Enum<?>>> SCHEMA_ENUMS = Map.of(
            "MallInquiryStatus", MallInquiryStatus.class,
            "MallInquiryCloseReason", MallInquiryCloseReason.class,
            "MallInquiryInvitationStatus", MallInquiryInvitationStatus.class,
            "MallInquiryQuoteStatus", MallInquiryQuoteStatus.class,
            "MallInquirySort", MallInquirySort.class,
            "MallInquiryBuyerKind", MallInquiryBuyerKind.class,
            "MallMerchantKind", MallMerchantKind.class);

    private static final List<Class<?>> CONTROLLERS = List.of(MallInquiryController.class,
            MallInquiryMerchantController.class);

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
                "GET /api/v1/mall/inquiry-offers",
                "POST /api/v1/mall/inquiries",
                "GET /api/v1/mall/inquiries",
                "GET /api/v1/mall/inquiries/{inquiryId}",
                "POST /api/v1/mall/inquiries/{inquiryId}/choice",
                "POST /api/v1/mall/inquiries/{inquiryId}/close",
                "GET /api/v1/provider/mall/inquiries",
                "GET /api/v1/provider/mall/inquiries/{inquiryId}",
                "PUT /api/v1/provider/mall/inquiries/{inquiryId}/quote",
                "POST /api/v1/provider/mall/inquiries/{inquiryId}/decline");
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
        assertThat(declaredKeys).hasSize(10);
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
        assertThat(map(size.get("schema")).get("maximum")).isEqualTo(MallInquiryService.MAX_PAGE_SIZE);
    }

    // 幂等键只在提交询价时要；If-Match 只在选定报价时要。返回询价单的客户接口都带 ETag。
    @Test
    void onlySubmittingTakesTheIdempotencyKeyAndOnlyChoosingTakesIfMatch() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Operation operation : allOperations()) {
            headers.put(operation.key(), list(declared(operation).get("parameters")).stream()
                    .map(entry -> resolve(map(entry)))
                    .filter(parameter -> "header".equals(parameter.get("in")))
                    .map(parameter -> (String) parameter.get("name"))
                    .toList());
        }
        assertThat(headers).containsEntry("POST /api/v1/mall/inquiries", List.of("Idempotency-Key"))
                .containsEntry("POST /api/v1/mall/inquiries/{inquiryId}/choice", List.of("If-Match"));
        assertThat(headers.values().stream().filter(names -> !names.isEmpty())).hasSize(2);
        for (Operation operation : allOperations()) {
            Map<String, Object> responses = map(declared(operation).get("responses"));
            boolean withETag = operation.method().getDeclaringClass() == MallInquiryController.class
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
        assertThat(successCodes("post", "/api/v1/mall/inquiries")).containsExactlyInAnyOrder("201", "200");
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
                    if (element == UUID.class) {
                        assertThat(items).as(field + " items").containsEntry("type", "string")
                                .containsEntry("format", "uuid");
                    } else {
                        assertThat(items.get("$ref")).as(field + " items")
                                .isEqualTo(SCHEMA_PREFIX + schemaOf(all, element));
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
        // 排序参数是 MallInquirySort 的取值。
        Map<String, Object> sort = list(map(map(map(root().get("paths")).get("/api/v1/mall/inquiries/{inquiryId}"))
                .get("get")).get("parameters")).stream().map(entry -> resolve(map(entry)))
                .filter(parameter -> "sort".equals(parameter.get("name"))).findFirst().orElseThrow();
        assertThat(refOf(map(sort.get("schema")))).isEqualTo(SCHEMA_PREFIX + "MallInquirySort");
    }

    @Test
    void limitsInTheContractAreTheOnesTheServiceEnforces() {
        Map<String, Object> schemas = schemas();
        Map<String, Object> inquiry = map(map(schemas.get("MallInquiryRequest")).get("properties"));
        assertThat(map(inquiry.get("requirement")).get("maxLength")).isEqualTo(MallInquiryService.MAX_REQUIREMENT);
        assertThat(map(inquiry.get("quantity")).get("maximum")).isEqualTo(MallInquiryService.MAX_QUANTITY);
        assertThat(map(inquiry.get("offerIds")).get("minItems")).isEqualTo(1);
        assertThat(map(inquiry.get("offerIds")).get("maxItems")).isEqualTo(MallInquiryService.MAX_MERCHANTS);
        Map<String, Object> quote = map(map(schemas.get("MallInquiryQuoteRequest")).get("properties"));
        assertThat(map(quote.get("unitAmountMinor")).get("minimum")).isEqualTo(1);
        assertThat(((Number) map(quote.get("unitAmountMinor")).get("maximum")).longValue())
                .isEqualTo(MallInquiryService.MAX_AMOUNT_MINOR);
        assertThat(map(quote.get("model")).get("maxLength")).isEqualTo(MallInquiryService.MAX_MODEL);
        assertThat(map(quote.get("leadDays")).get("maximum")).isEqualTo(MallInquiryService.MAX_LEAD_DAYS);
        assertThat(map(quote.get("includes")).get("maxLength")).isEqualTo(MallInquiryService.MAX_INCLUDES);
        assertThat(map(map(map(schemas.get("MallInquiryDeclineRequest")).get("properties")).get("reason"))
                .get("maxLength")).isEqualTo(MallInquiryService.MAX_REASON);
        assertThat(contractText()).contains("72 小时").contains("不超过 7 天");
        assertThat(MallInquiryService.QUOTE_WINDOW).hasHours(72);
        assertThat(MallInquiryService.MAX_QUOTE_VALIDITY).hasDays(7);
    }

    @Test
    void everyBusinessErrorCodeIsDeclaredUnderTheStatusTheHandlerAnswers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Set<String> produced = new TreeSet<>();
        for (Error error : Error.values()) {
            ResponseEntity<ApiError> response = handler.handleMallInquiry(new MallInquiryException(error),
                    new MockHttpServletRequest());
            String code = response.getBody().code();
            String status = String.valueOf(response.getStatusCode().value());
            if (!code.startsWith("MALL_INQUIRY_")) {
                assertThat(code).as(error.name()).isIn("COMMON_NOT_FOUND", "COMMON_FORBIDDEN");
                continue;
            }
            produced.add(code);
            // 报价不是选定的那份：只在确认订单预览和提交订单时出现，写在那两份契约里。
            if (CHECKOUT_ONLY.contains(code)) {
                for (Path checkout : List.of(CART_CONTRACT, TRADE_CONTRACT)) {
                    assertThat(read(checkout)).as(code + " in " + checkout.getFileName()).contains(code);
                }
                continue;
            }
            assertThat(allOperations().stream()
                    .map(operation -> resolveResponse(map(map(declared(operation).get("responses")).get(status))))
                    .filter(declaredResponse -> declaredResponse != null && declaredResponse.get("description") != null)
                    .map(declaredResponse -> (String) declaredResponse.get("description")))
                    .as(code + " under " + status)
                    .anyMatch(description -> description.contains(code));
        }
        assertThat(strings(map(schemas().get("MallInquiryErrorCode")).get("enum")))
                .containsExactlyInAnyOrderElementsOf(produced);
        Set<String> mentioned = new TreeSet<>();
        Matcher matcher = Pattern.compile("MALL_INQUIRY_[A-Z_]+").matcher(contractText());
        while (matcher.find()) {
            mentioned.add(matcher.group());
        }
        assertThat(mentioned).containsExactlyElementsOf(produced);
        // 下单时能遇到的询价错误码，确认订单预览和提交订单的契约都写了。
        for (Path checkout : List.of(CART_CONTRACT, TRADE_CONTRACT)) {
            assertThat(read(checkout)).as(checkout.getFileName().toString())
                    .contains("MALL_INQUIRY_QUOTE_NOT_CHOSEN", "MALL_INQUIRY_QUOTE_EXPIRED");
        }
    }

    @Test
    void everySchemaInTheContractIsCoveredByThisTest() {
        List<String> covered = new ArrayList<>(SCHEMA_RECORDS.keySet());
        covered.addAll(REQUEST_RECORDS.keySet());
        covered.addAll(SCHEMA_ENUMS.keySet());
        covered.add("MallInquiryErrorCode");
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
