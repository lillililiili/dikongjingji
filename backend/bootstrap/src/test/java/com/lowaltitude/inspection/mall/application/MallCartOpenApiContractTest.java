package com.lowaltitude.inspection.mall.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
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

import com.lowaltitude.inspection.account.application.AddressView;
import com.lowaltitude.inspection.bootstrap.api.ApiError;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.mall.application.MallCartException.Error;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.yaml.snakeyaml.Yaml;

/**
 * 契约和实现逐项对照：购物车控制器的每个路由和方法都在契约里并且要登录，参数、请求体、返回对象都指向对应的 schema；
 * 每个对象的字段与 schema 的 properties、required、类型一致，枚举取值一致，数量上限与服务一致，
 * 业务错误码与全局异常处理给出的一致。收货地址引用 openapi-account.yaml，商家和图片与商品目录契约同形。
 */
class MallCartOpenApiContractTest {

    private static final Path CONTRACTS = Path.of("..", "..", "contracts");
    private static final Path CONTRACT = CONTRACTS.resolve("openapi-mall-cart.yaml");
    private static final String SCHEMA_PREFIX = "#/components/schemas/";

    /** 返回对象：字段全部必有，可以为空的用 null 表示。 */
    private static final Map<String, Class<? extends Record>> SCHEMA_RECORDS = Map.ofEntries(
            Map.entry("Cart", CartView.class),
            Map.entry("CartGroup", CartGroupView.class),
            Map.entry("CartItem", CartItemView.class),
            Map.entry("CartCount", CartCountView.class),
            Map.entry("CartAdded", CartAddedView.class),
            Map.entry("CartQuantity", CartQuantityView.class),
            Map.entry("CartRemoved", CartRemovedView.class),
            Map.entry("CheckoutLine", CheckoutLineView.class),
            Map.entry("DeliveryOption", DeliveryOptionView.class),
            Map.entry("CheckoutAmounts", CheckoutAmountsView.class),
            Map.entry("CheckoutPreview", MallCartController.CheckoutPreviewView.class),
            Map.entry("MallMerchant", MallMerchantView.class),
            Map.entry("MediaAsset", MallMediaView.class));

    /** 别的契约里的对象。 */
    private static final Map<Class<?>, String> EXTERNAL_RECORDS = Map.of(
            AddressView.class, "./openapi-account.yaml#/components/schemas/Address");

    /** 请求体：字段名与控制器的请求对象一致，服务要求全部给出。 */
    private static final Map<String, Class<? extends Record>> REQUEST_RECORDS = Map.of(
            "AddCartItemRequest", MallCartController.AddRequest.class,
            "SetCartQuantityRequest", MallCartController.QuantityRequest.class,
            "RemoveCartItemsRequest", MallCartController.RemoveRequest.class,
            "CartCityRequest", MallCartController.CityRequest.class);

    private static final Map<String, Class<? extends Enum<?>>> SCHEMA_ENUMS = Map.of(
            "MallMerchantKind", MallMerchantKind.class,
            "DeliveryMode", DeliveryMode.class,
            "CartInvalidReason", CartInvalidReason.class,
            "CheckoutBlocker", MallCartController.CheckoutBlocker.class);

    @Test
    void contractIsVersionOneAndDeclaresBearerJwt() {
        Map<String, Object> root = root(CONTRACT);

        assertThat(map(root.get("info")).get("version")).isEqualTo("1.2.1");
        assertThat(map(map(root.get("components")).get("securitySchemes")).get("bearerAuth"))
                .isEqualTo(Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT"));
        assertThat(root).doesNotContainKey("security");
    }

    @Test
    void everyRouteIsDeclaredWithItsMethodAndNeedsALogin() {
        List<Operation> operations = operations();

        assertThat(operations).extracting(Operation::key).containsExactlyInAnyOrder(
                "GET /api/v1/mall/cart",
                "GET /api/v1/mall/cart/count",
                "POST /api/v1/mall/cart/items",
                "PUT /api/v1/mall/cart/items/{cartItemId}",
                "POST /api/v1/mall/cart/remove",
                "POST /api/v1/mall/cart/remove-invalid",
                "GET /api/v1/mall/checkout-preview");
        assertThat(operations).allSatisfy(operation -> {
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

    @Test
    void contractDeclaresNoOperationTheControllerDoesNotHave() {
        Set<String> implemented = new TreeSet<>();
        operations().forEach(operation -> implemented.add(operation.key()));
        Set<String> declared = new TreeSet<>();
        map(root(CONTRACT).get("paths")).forEach((path, item) -> map(item).keySet()
                .forEach(method -> declared.add(method.toUpperCase() + " " + path)));

        assertThat(implemented).hasSize(7);
        assertThat(declared).containsExactlyElementsOf(implemented);
    }

    @Test
    void everyParameterOfEveryRouteIsDeclaredInTheSamePlace() {
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
                    implemented.add("query " + name(query.name(), query.value(), parameter));
                }
                if (path != null) {
                    String key = "path " + name(path.name(), path.value(), parameter);
                    implemented.add(key);
                    assertThat(declared.get(key)).as(operation.key() + " " + key).isNotNull();
                    assertThat(declared.get(key).get("required")).as(key).isEqualTo(true);
                }
            }
            assertThat(declared.keySet())
                    .as(operation.key() + " parameters")
                    .containsExactlyInAnyOrderElementsOf(implemented);
        });
    }

    @Test
    void everyRequestBodyAndSuccessResponsePointsAtTheSchemaOfItsJavaType() {
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
                assertThat(requestBody).as(operation.key() + " requestBody").isNotNull();
                assertThat(requestBody.get("required"))
                        .as(operation.key() + " requestBody required")
                        .isEqualTo(body.getAnnotation(RequestBody.class).required());
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
        // 加入购物车是 201，其余都是 200。
        assertThat(successCodes("post", "/api/v1/mall/cart/items")).containsExactly("201");
        assertThat(successCodes("put", "/api/v1/mall/cart/items/{cartItemId}")).containsExactly("200");
    }

    @Test
    void everyResponseObjectHasExactlyTheFieldsTheContractRequires() {
        Map<String, Object> schemas = schemas(CONTRACT);

        assertThat(SCHEMA_RECORDS).allSatisfy((schemaName, recordType) ->
                assertExactFields(schemas, schemaName, recordType));
        // 引用的收货地址也和 Java 对象一致。
        assertExactFields(schemas(CONTRACTS.resolve("openapi-account.yaml")), "Address", AddressView.class);
    }

    @Test
    void everyRequestObjectHasTheControllersFieldsAndAllOfThemAreRequired() {
        Map<String, Object> schemas = schemas(CONTRACT);

        assertThat(REQUEST_RECORDS).allSatisfy((schemaName, recordType) ->
                assertExactFields(schemas, schemaName, recordType));
        // 控制器里的请求对象都登记在上面了（预览的返回对象另外登记）。
        assertThat(Arrays.stream(MallCartController.class.getDeclaredClasses())
                .filter(Class::isRecord)
                .filter(type -> !Modifier.isPrivate(type.getModifiers()))
                .filter(type -> type != MallCartController.CheckoutPreviewView.class))
                .containsExactlyInAnyOrderElementsOf(REQUEST_RECORDS.values());
    }

    // 字段名对了还不够：每个字段的类型、格式和指向的 schema 也要和 Java 类型对得上，基本类型不能声明成可空。
    @Test
    void everyFieldIsDeclaredWithTheTypeOfItsJavaComponent() {
        Map<String, Object> schemas = schemas(CONTRACT);
        Map<String, Class<? extends Record>> records = new LinkedHashMap<>(SCHEMA_RECORDS);
        records.putAll(REQUEST_RECORDS);

        assertThat(records).allSatisfy((schemaName, recordType) -> {
            Map<String, Object> properties = map(map(schemas.get(schemaName)).get("properties"));
            for (RecordComponent component : recordType.getRecordComponents()) {
                String field = schemaName + "." + component.getName();
                Map<String, Object> property = map(properties.get(component.getName()));
                Class<?> type = component.getType();

                if (type.isRecord() || type.isEnum()) {
                    assertThat(refOf(property)).as(field).isEqualTo(expectedRef(type));
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
                if (type == List.class) {
                    Class<?> element = (Class<?>) ((ParameterizedType) component.getGenericType())
                            .getActualTypeArguments()[0];
                    Map<String, Object> items = map(property.get("items"));
                    if (element.isRecord() || element.isEnum()) {
                        assertThat(items.get("$ref")).as(field + " items").isEqualTo(expectedRef(element));
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
        Map<String, Object> schemas = schemas(CONTRACT);

        assertThat(SCHEMA_ENUMS).allSatisfy((schemaName, enumType) ->
                assertThat(strings(map(schemas.get(schemaName)).get("enum")))
                        .as(schemaName)
                        .containsExactlyElementsOf(Arrays.stream(enumType.getEnumConstants())
                                .map(Enum::name)
                                .toList()));
    }

    // 商家、图片两个对象在这份契约里另写了一份，必须和商品目录契约里的一模一样（说明文字除外）。
    @Test
    void theMerchantAndMediaSchemasAreTheSameAsInTheCatalogContract() {
        Map<String, Object> cart = schemas(CONTRACT);
        Map<String, Object> catalog = schemas(CONTRACTS.resolve("openapi-mall-catalog.yaml"));

        for (String schemaName : List.of("MallMerchant", "MediaAsset", "MallMerchantKind")) {
            Map<String, Object> mine = new LinkedHashMap<>(map(cart.get(schemaName)));
            Map<String, Object> theirs = new LinkedHashMap<>(map(catalog.get(schemaName)));
            mine.remove("description");
            theirs.remove("description");
            assertThat(mine).as(schemaName).isEqualTo(theirs);
        }
    }

    // 契约写的数量上限就是服务校验的那些。
    @Test
    void limitsInTheContractAreTheOnesTheServiceEnforces() {
        Map<String, Object> schemas = schemas(CONTRACT);
        Map<String, Object> cartItemIds = list(declared("get", "/api/v1/mall/checkout-preview").get("parameters"))
                .stream()
                .map(entry -> resolve(map(entry)))
                .filter(parameter -> "cartItemIds".equals(parameter.get("name")))
                .findFirst()
                .orElseThrow();

        assertThat(map(cartItemIds.get("schema")).get("maxItems")).isEqualTo(MallCartService.MAX_CHECKOUT_LINES);
        assertThat(map(map(map(schemas.get("RemoveCartItemsRequest")).get("properties")).get("cartItemIds"))
                .get("maxItems"))
                .isEqualTo(MallCartService.MAX_LINES);
        for (String schemaName : List.of(
                "AddCartItemRequest", "SetCartQuantityRequest", "CartItem", "CartQuantity", "CheckoutLine")) {
            Map<String, Object> quantity = map(map(map(schemas.get(schemaName)).get("properties")).get("quantity"));
            assertThat(quantity.get("minimum")).as(schemaName).isEqualTo(1);
            assertThat(quantity.get("maximum")).as(schemaName).isEqualTo(MallCartService.MAX_QUANTITY);
        }
        assertThat(contractText()).contains("最多 50 行").contains("1–20 行").contains("1–99");
    }

    // 每一种业务拒绝：全局异常处理给出的状态码和错误码，契约里至少有一个接口在同一个状态码下写了它。
    @Test
    void everyBusinessErrorCodeIsDeclaredUnderTheStatusTheHandlerAnswers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Set<String> produced = new TreeSet<>();
        for (Error error : Error.values()) {
            ResponseEntity<ApiError> response = handler.handleMallCart(
                    new MallCartException(error), new MockHttpServletRequest());
            String code = response.getBody().code();
            String status = String.valueOf(response.getStatusCode().value());
            List<String> descriptions = operations().stream()
                    .map(operation -> map(map(declared(operation).get("responses")).get(status)))
                    .filter(declaredResponse -> declaredResponse != null)
                    .map(declaredResponse -> (String) resolveResponse(declaredResponse).get("description"))
                    .toList();
            if (!code.startsWith("MALL_")) {
                assertThat(code).as(error.name()).isEqualTo("COMMON_NOT_FOUND");
                assertThat(status).isEqualTo("404");
                assertThat(descriptions).as(code).anyMatch(description -> description.contains(code));
                continue;
            }
            produced.add(code);
            assertThat(descriptions).as(code + " under " + status).anyMatch(description -> description.contains(code));
        }

        assertThat(strings(map(schemas(CONTRACT).get("MallCartErrorCode")).get("enum")))
                .containsExactlyInAnyOrderElementsOf(produced);
        // 反过来，契约里提到的商城错误码都是服务端真的会给出的。
        Set<String> mentioned = new TreeSet<>();
        Matcher matcher = Pattern.compile("MALL_(CART|CHECKOUT)_[A-Z_]+").matcher(contractText());
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
        covered.add("MallCartErrorCode");
        covered.add("ApiError");

        assertThat(schemas(CONTRACT).keySet()).containsExactlyInAnyOrderElementsOf(covered);
    }

    // ---- 控制器一侧 ----

    private record Operation(String httpMethod, String path, Method method) {
        String key() {
            return httpMethod + " " + path;
        }
    }

    private static List<Operation> operations() {
        List<Operation> operations = new ArrayList<>();
        for (Method method : MallCartController.class.getDeclaredMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping != null) {
                assertThat(mapping.path()).as(method.getName()).hasSize(1);
                assertThat(mapping.method()).as(method.getName()).hasSize(1);
                operations.add(new Operation(mapping.method()[0].name(), mapping.path()[0], method));
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

    private static void assertExactFields(
            Map<String, Object> schemas,
            String schemaName,
            Class<? extends Record> recordType
    ) {
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
    }

    private static String expectedRef(Class<?> type) {
        if (EXTERNAL_RECORDS.containsKey(type)) {
            return EXTERNAL_RECORDS.get(type);
        }
        return SCHEMA_PREFIX + (type.isRecord() ? schemaOf(SCHEMA_RECORDS, type) : schemaOf(SCHEMA_ENUMS, type));
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
        if (type == String.class || type == UUID.class) {
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

    private static Map<String, Object> root(Path contract) {
        try {
            return new Yaml().load(Files.readString(contract));
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static Map<String, Object> schemas(Path contract) {
        return map(map(root(contract).get("components")).get("schemas"));
    }

    private static Map<String, Object> declared(Operation operation) {
        return declared(operation.httpMethod().toLowerCase(), operation.path());
    }

    private static Map<String, Object> declared(String httpMethod, String path) {
        Map<String, Object> item = map(map(root(CONTRACT).get("paths")).get(path));
        return item == null ? null : map(item.get(httpMethod));
    }

    private static List<String> successCodes(String httpMethod, String path) {
        Map<String, Object> responses = map(declared(httpMethod, path).get("responses"));
        return responses.keySet().stream().filter(code -> code.startsWith("2")).toList();
    }

    private static Map<String, Object> resolve(Map<String, Object> parameter) {
        Object ref = parameter.get("$ref");
        if (ref == null) {
            return parameter;
        }
        String name = ((String) ref).substring("#/components/parameters/".length());
        return map(map(map(root(CONTRACT).get("components")).get("parameters")).get(name));
    }

    /** 响应可以直接写，也可以引用 components.responses 里的。 */
    private static Map<String, Object> resolveResponse(Map<String, Object> response) {
        Object ref = response.get("$ref");
        if (ref == null) {
            return response;
        }
        String name = ((String) ref).substring("#/components/responses/".length());
        return map(map(map(root(CONTRACT).get("components")).get("responses")).get(name));
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
