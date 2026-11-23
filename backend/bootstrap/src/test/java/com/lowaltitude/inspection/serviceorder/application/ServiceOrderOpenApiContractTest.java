package com.lowaltitude.inspection.serviceorder.application;

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
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderCustomerKind;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderDeliveryStatus;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderEventType;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderParty;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderRefundKind;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderStatus;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderType;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderException.Error;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.DownloadIntentView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderActionsView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderCustomerView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderDeliveryView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderEventView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderFileView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderLineView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderPageView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderPaymentStartView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderPaymentView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderPlanView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderProviderView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderQuoteView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderRefundView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderSummaryView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderView;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.yaml.snakeyaml.Yaml;

/**
 * 契约 contracts/openapi-service-order.yaml 和实现逐项对照（S4-3）：两个控制器的每个路由都在契约里；除了短时下载链接都要登录；
 * 参数、请求头、请求体、返回对象指向对应的 schema；每个对象的字段、类型、枚举与 Java 一致；上限与服务一致；
 * 业务错误码与全局异常处理给出的一致。
 */
class ServiceOrderOpenApiContractTest {

    private static final Path CONTRACT = Path.of("..", "..", "contracts", "openapi-service-order.yaml");
    private static final String SCHEMA_PREFIX = "#/components/schemas/";
    private static final String DOWNLOAD = "GET /api/v1/service-order-files/{token}";

    private static final Map<String, Class<? extends Record>> SCHEMA_RECORDS = Map.ofEntries(
            Map.entry("ServiceOrderCustomer", ServiceOrderCustomerView.class),
            Map.entry("ServiceOrderProvider", ServiceOrderProviderView.class),
            Map.entry("ServiceOrderLine", ServiceOrderLineView.class),
            Map.entry("ServiceOrderQuote", ServiceOrderQuoteView.class),
            Map.entry("ServiceOrderPlan", ServiceOrderPlanView.class),
            Map.entry("ServiceOrderRefund", ServiceOrderRefundView.class),
            Map.entry("ServiceOrderPayment", ServiceOrderPaymentView.class),
            Map.entry("ServiceOrderFile", ServiceOrderFileView.class),
            Map.entry("ServiceOrderDelivery", ServiceOrderDeliveryView.class),
            Map.entry("ServiceOrderEvent", ServiceOrderEventView.class),
            Map.entry("ServiceOrderActions", ServiceOrderActionsView.class),
            Map.entry("ServiceOrder", ServiceOrderView.class),
            Map.entry("ServiceOrderSummary", ServiceOrderSummaryView.class),
            Map.entry("ServiceOrderPage", ServiceOrderPageView.class),
            Map.entry("ServiceOrderPaymentStart", ServiceOrderPaymentStartView.class),
            Map.entry("DownloadIntent", DownloadIntentView.class));

    private static final Map<String, Class<? extends Record>> REQUEST_RECORDS = Map.of(
            "PlanConfirmRequest", ServiceOrderController.PlanConfirmRequest.class,
            "CustomerCancelRequest", ServiceOrderController.CustomerCancelRequest.class,
            "AcceptRequest", ServiceOrderController.AcceptRequest.class,
            "ReworkRequest", ServiceOrderController.ReworkRequest.class,
            "PlanRequest", ServiceOrderProviderController.PlanRequest.class,
            "DeliveryRequest", ServiceOrderProviderController.DeliveryRequest.class,
            "ReasonRequest", ServiceOrderProviderController.ReasonRequest.class,
            "TerminateRequest", ServiceOrderProviderController.TerminateRequest.class);

    private static final Map<String, List<String>> REQUEST_REQUIRED = Map.of(
            "PlanConfirmRequest", List.of("planVersion", "agreed"),
            "CustomerCancelRequest", List.of(),
            "AcceptRequest", List.of("round"),
            "ReworkRequest", List.of("round", "reason"),
            "PlanRequest", List.of("schedule", "terms"),
            "DeliveryRequest", List.of("note", "fileIds"),
            "ReasonRequest", List.of("reason"),
            "TerminateRequest", List.of("refundAmountMinor", "reason"));

    private static final Map<String, Class<? extends Enum<?>>> SCHEMA_ENUMS = Map.of(
            "ServiceOrderStatus", ServiceOrderStatus.class,
            "ServiceOrderType", ServiceOrderType.class,
            "ServiceOrderCustomerKind", ServiceOrderCustomerKind.class,
            "ServiceOrderParty", ServiceOrderParty.class,
            "ServiceOrderDeliveryStatus", ServiceOrderDeliveryStatus.class,
            "ServiceOrderRefundKind", ServiceOrderRefundKind.class,
            "ServiceOrderEventType", ServiceOrderEventType.class);

    private static final List<Class<?>> CONTROLLERS = List.of(ServiceOrderController.class,
            ServiceOrderProviderController.class);

    @Test
    void contractIsVersionOneAndDeclaresBearerJwt() {
        Map<String, Object> root = root();

        assertThat(map(root.get("info")).get("version")).isEqualTo("1.0.0");
        assertThat(map(map(map(root.get("components")).get("securitySchemes")).get("bearerAuth")))
                .isEqualTo(Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT"));
    }

    @Test
    void everyRouteIsDeclaredAndAllButTheDownloadLinkNeedALogin() {
        List<Operation> operations = allOperations();

        assertThat(operations).extracting(Operation::key).containsExactlyInAnyOrder(
                "GET /api/v1/service-orders",
                "GET /api/v1/service-orders/{orderId}",
                "POST /api/v1/service-orders/{orderId}/plan-confirmation",
                "POST /api/v1/service-orders/{orderId}/payments",
                "POST /api/v1/service-orders/{orderId}/cancel",
                "POST /api/v1/service-orders/{orderId}/acceptance",
                "POST /api/v1/service-orders/{orderId}/rework",
                "POST /api/v1/service-orders/{orderId}/files/{fileId}/download-intents",
                DOWNLOAD,
                "GET /api/v1/provider/service-orders",
                "GET /api/v1/provider/service-orders/{orderId}",
                "PUT /api/v1/provider/service-orders/{orderId}/plan",
                "POST /api/v1/provider/service-orders/{orderId}/start",
                "POST /api/v1/provider/service-orders/{orderId}/files",
                "DELETE /api/v1/provider/service-orders/{orderId}/files/{fileId}",
                "GET /api/v1/provider/service-orders/{orderId}/files/{fileId}/content",
                "POST /api/v1/provider/service-orders/{orderId}/delivery",
                "POST /api/v1/provider/service-orders/{orderId}/cancel",
                "POST /api/v1/provider/service-orders/{orderId}/termination");
        assertThat(operations).allSatisfy(operation -> {
            Map<String, Object> declared = declared(operation);
            assertThat(declared).as(operation.key()).isNotNull();
            Object security = declared.get("security");
            if (operation.key().equals(DOWNLOAD)) {
                assertThat(security).isEqualTo(List.of());
            } else {
                assertThat(security).as(operation.key()).isEqualTo(List.of(Map.of("bearerAuth", List.of())));
                assertThat(map(declared.get("responses")).keySet()).as(operation.key()).contains("400", "401", "403");
            }
            if (operation.path().contains("{")) {
                assertThat(map(declared.get("responses"))).as(operation.key()).containsKey("404");
            }
        });
        Set<String> declaredKeys = new TreeSet<>();
        map(root().get("paths")).forEach((path, item) -> map(item).keySet()
                .forEach(method -> declaredKeys.add(method.toUpperCase() + " " + path)));
        assertThat(declaredKeys).hasSize(operations.size());
    }

    @Test
    void everyParameterIsDeclaredAndOnlyWritesTakeIfMatch() {
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
                    assertThat(key).isEqualTo("header If-Match");
                    assertThat(declared.get(key).get("required")).isEqualTo(true);
                }
            }
            assertThat(declared.keySet()).as(operation.key()).containsExactlyInAnyOrderElementsOf(implemented);
        });
        Map<String, Object> size = map(map(map(root().get("components")).get("parameters")).get("Size"));
        assertThat(map(size.get("schema")).get("maximum")).isEqualTo(ServiceOrderService.MAX_PAGE_SIZE);
    }

    @Test
    void everyRequestBodyAndSuccessResponsePointsAtTheSchemaOfItsJavaType() {
        assertThat(allOperations()).allSatisfy(operation -> {
            Map<String, Object> declared = declared(operation);
            Parameter body = Arrays.stream(operation.method().getParameters())
                    .filter(parameter -> parameter.isAnnotationPresent(RequestBody.class))
                    .findFirst().orElse(null);
            boolean multipart = Arrays.stream(operation.method().getParameters())
                    .anyMatch(parameter -> parameter.isAnnotationPresent(RequestPart.class));
            if (multipart) {
                assertThat(map(map(declared.get("requestBody")).get("content"))).containsKey("multipart/form-data");
            } else if (body == null) {
                assertThat(declared).as(operation.key()).doesNotContainKey("requestBody");
            } else {
                assertThat(jsonSchemaRef(map(declared.get("requestBody")))).as(operation.key())
                        .isEqualTo(SCHEMA_PREFIX + schemaOf(REQUEST_RECORDS, body.getType()));
            }
            Map<String, Object> responses = map(declared.get("responses"));
            List<String> success = responses.keySet().stream().filter(code -> code.startsWith("2")).toList();
            Class<?> returned = returnedBody(operation.method());
            assertThat(success).as(operation.key()).hasSize(1);
            Map<String, Object> response = map(responses.get(success.getFirst()));
            if (returned == byte[].class) {
                assertThat(map(response.get("content"))).containsKey("application/octet-stream");
            } else if (returned == Void.class) {
                assertThat(success.getFirst()).isEqualTo("204");
            } else {
                assertThat(jsonSchemaRef(response)).as(operation.key())
                        .isEqualTo(SCHEMA_PREFIX + schemaOf(SCHEMA_RECORDS, returned));
                boolean single = returned == ServiceOrderView.class;
                assertThat(response.containsKey("headers")).as(operation.key() + " ETag").isEqualTo(single);
            }
        });
    }

    @Test
    void everyObjectHasExactlyItsFieldsWithTheirTypes() {
        Map<String, Object> schemas = schemas();
        Map<String, Class<? extends Record>> all = new LinkedHashMap<>(SCHEMA_RECORDS);
        all.putAll(REQUEST_RECORDS);
        all.forEach((schemaName, recordType) -> {
            Map<String, Object> schema = map(schemas.get(schemaName));
            List<String> fields = Arrays.stream(recordType.getRecordComponents()).map(RecordComponent::getName).toList();
            assertThat(schema.get("additionalProperties")).as(schemaName).isEqualTo(false);
            assertThat(map(schema.get("properties")).keySet()).as(schemaName).containsExactlyInAnyOrderElementsOf(fields);
            List<String> required = schema.get("required") == null ? List.of() : strings(schema.get("required"));
            assertThat(required).as(schemaName).containsExactlyInAnyOrderElementsOf(
                    REQUEST_RECORDS.containsKey(schemaName) ? REQUEST_REQUIRED.get(schemaName) : fields);
            Map<String, Object> properties = map(schema.get("properties"));
            for (RecordComponent component : recordType.getRecordComponents()) {
                String field = schemaName + "." + component.getName();
                Map<String, Object> property = map(properties.get(component.getName()));
                Class<?> type = component.getType();
                if (type.isRecord() || type.isEnum()) {
                    assertThat(refOf(property)).as(field).isEqualTo(SCHEMA_PREFIX + (type.isRecord()
                            ? schemaOf(SCHEMA_RECORDS, type) : schemaOf(SCHEMA_ENUMS, type)));
                    continue;
                }
                List<String> declaredTypes = types(property);
                assertThat(declaredTypes).as(field).contains(jsonType(type));
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
                    if (element == String.class || element == UUID.class) {
                        assertThat(items.get("type")).as(field + " items").isEqualTo("string");
                    } else {
                        assertThat(items.get("$ref")).as(field + " items")
                                .isEqualTo(SCHEMA_PREFIX + schemaOf(SCHEMA_RECORDS, element));
                    }
                }
            }
        });
    }

    @Test
    void enumsAndLimitsMatchTheService() {
        Map<String, Object> schemas = schemas();
        SCHEMA_ENUMS.forEach((schemaName, enumType) ->
                assertThat(strings(map(schemas.get(schemaName)).get("enum"))).as(schemaName)
                        .containsExactlyElementsOf(Arrays.stream(enumType.getEnumConstants()).map(Enum::name).toList()));
        Map<String, Object> plan = map(map(schemas.get("PlanRequest")).get("properties"));
        assertThat(map(plan.get("schedule")).get("maxLength")).isEqualTo(ServiceOrderService.MAX_SCHEDULE);
        assertThat(map(plan.get("terms")).get("maxItems")).isEqualTo(ServiceOrderService.MAX_TERMS);
        assertThat(map(map(plan.get("terms")).get("items")).get("maxLength")).isEqualTo(ServiceOrderService.MAX_TERM);
        assertThat(map(plan.get("note")).get("maxLength")).isEqualTo(ServiceOrderService.MAX_PLAN_NOTE);
        Map<String, Object> delivery = map(map(schemas.get("DeliveryRequest")).get("properties"));
        assertThat(map(delivery.get("note")).get("maxLength")).isEqualTo(ServiceOrderService.MAX_DELIVERY_NOTE);
        assertThat(map(delivery.get("fileIds")).get("maxItems")).isEqualTo(ServiceOrderService.MAX_DELIVERY_FILES);
        assertThat(map(map(map(schemas.get("ReworkRequest")).get("properties")).get("reason")).get("maxLength"))
                .isEqualTo(ServiceOrderService.MAX_REVIEW_REASON);
        assertThat(map(map(map(schemas.get("ReasonRequest")).get("properties")).get("reason")).get("maxLength"))
                .isEqualTo(ServiceOrderService.MAX_CANCEL_REASON);
        assertThat(map(map(map(schemas.get("TerminateRequest")).get("properties")).get("reason")).get("maxLength"))
                .isEqualTo(ServiceOrderService.MAX_TERMINATE_REASON);
        assertThat(contractText()).contains("15 分钟").contains("10 分钟").contains("20 MB");
        assertThat(ServiceOrderService.PAY_WINDOW).hasMinutes(15);
        assertThat(ServiceOrderService.DOWNLOAD_TTL).hasMinutes(10);
        assertThat(ServiceOrderService.MAX_FILE_BYTES).isEqualTo(20L * 1024 * 1024);
    }

    @Test
    void everyBusinessErrorCodeIsDeclaredUnderTheStatusTheHandlerAnswers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Set<String> produced = new TreeSet<>();
        for (Error error : Error.values()) {
            ResponseEntity<ApiError> response = handler.handleServiceOrder(new ServiceOrderException(error),
                    new MockHttpServletRequest());
            String code = response.getBody().code();
            String status = String.valueOf(response.getStatusCode().value());
            if (!code.startsWith("SERVICE_ORDER_")) {
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
        assertThat(strings(map(schemas().get("ServiceOrderErrorCode")).get("enum")))
                .containsExactlyInAnyOrderElementsOf(produced);
        Set<String> mentioned = new TreeSet<>();
        Matcher matcher = Pattern.compile("SERVICE_ORDER_[A-Z_]+").matcher(contractText());
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
        covered.add("ServiceOrderErrorCode");
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
            Type argument = parameterized.getActualTypeArguments()[0];
            return argument instanceof Class<?> raw ? raw : byte[].class;
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
