package com.lowaltitude.inspection.account.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
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

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
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
 * 契约和实现逐项对照：地址控制器的每个路由和方法都在契约里并且要登录，参数、请求体、返回对象都指向对应的 schema；
 * 每个对象的字段与 schema 的 properties、required、类型一致，长度和数量上限与服务一致，
 * 业务错误码与全局异常处理给出的一致。这样改了一边忘了另一边会立刻失败。
 */
class AccountOpenApiContractTest {

    private static final Path CONTRACT = Path.of("..", "..", "contracts", "openapi-account.yaml");
    private static final String SCHEMA_PREFIX = "#/components/schemas/";

    /** 返回对象：字段全部必有。 */
    private static final Map<String, Class<? extends Record>> SCHEMA_RECORDS = Map.of(
            "AddressList", AddressListView.class,
            "Address", AddressView.class,
            "AddressRegion", AddressRegionView.class);

    /** 请求体：字段名与控制器收的对象一致；isDefault 可以不传。 */
    private static final Map<String, Class<? extends Record>> REQUEST_RECORDS = Map.of(
            "AddressForm", AddressForm.class);

    private static final Map<String, List<String>> REQUEST_REQUIRED = Map.of(
            "AddressForm", List.of("recipientName", "phone", "cityCode", "detail"));

    @Test
    void contractIsVersionOneAndDeclaresBearerJwt() {
        Map<String, Object> root = root();

        assertThat(map(root.get("info")).get("version")).isEqualTo("1.0.0");
        assertThat(map(map(root.get("components")).get("securitySchemes")).get("bearerAuth"))
                .isEqualTo(Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT"));
        // 没有全局的 security：每个操作自己写明要登录，漏写会被下面的用例发现。
        assertThat(root).doesNotContainKey("security");
    }

    @Test
    void everyRouteIsDeclaredWithItsMethodAndNeedsALogin() {
        List<Operation> operations = operations();

        assertThat(operations).extracting(Operation::key).containsExactlyInAnyOrder(
                "GET /api/v1/account/addresses",
                "POST /api/v1/account/addresses",
                "PUT /api/v1/account/addresses/{addressId}",
                "DELETE /api/v1/account/addresses/{addressId}");
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
        // 带地址 ID 的两个接口写明不是自己的地址时的 404。
        assertThat(map(declared("put", "/api/v1/account/addresses/{addressId}").get("responses"))).containsKey("404");
        assertThat(map(declared("delete", "/api/v1/account/addresses/{addressId}").get("responses")))
                .containsKey("404");
    }

    @Test
    void contractDeclaresNoOperationTheControllerDoesNotHave() {
        Set<String> implemented = new TreeSet<>();
        operations().forEach(operation -> implemented.add(operation.key()));
        Set<String> declared = new TreeSet<>();
        map(root().get("paths")).forEach((path, item) -> map(item).keySet()
                .forEach(method -> declared.add(method.toUpperCase() + " " + path)));

        assertThat(implemented).hasSize(4);
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
                    assertThat(map(declared.get(key).get("schema"))).containsEntry("format", "uuid");
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
            if (returned == Void.class) {
                assertThat(success).as(operation.key()).containsExactly("204");
                assertThat(map(responses.get("204"))).doesNotContainKey("content");
            } else {
                assertThat(success).allSatisfy(code -> assertThat(jsonSchemaRef(map(responses.get(code))))
                        .as(operation.key() + " " + code)
                        .isEqualTo(SCHEMA_PREFIX + schemaOf(SCHEMA_RECORDS, returned)));
            }
        });
        // 新增是 201，修改是 200。
        assertThat(successCodes("post", "/api/v1/account/addresses")).containsExactly("201");
        assertThat(successCodes("put", "/api/v1/account/addresses/{addressId}")).containsExactly("200");
    }

    @Test
    void everyResponseObjectHasExactlyTheFieldsTheContractRequires() {
        Map<String, Object> schemas = schemas();

        assertThat(SCHEMA_RECORDS).allSatisfy((schemaName, recordType) -> {
            Map<String, Object> schema = map(schemas.get(schemaName));
            List<String> fields = fields(recordType);

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
    void theRequestObjectHasTheFormsFieldsAndTheRequiredOnes() {
        Map<String, Object> schemas = schemas();

        assertThat(REQUEST_REQUIRED.keySet()).containsExactlyInAnyOrderElementsOf(REQUEST_RECORDS.keySet());
        assertThat(REQUEST_RECORDS).allSatisfy((schemaName, recordType) -> {
            Map<String, Object> schema = map(schemas.get(schemaName));

            assertThat(schema).as(schemaName).isNotNull();
            assertThat(schema.get("additionalProperties")).as(schemaName).isEqualTo(false);
            assertThat(map(schema.get("properties")).keySet())
                    .as(schemaName + " properties")
                    .containsExactlyInAnyOrderElementsOf(fields(recordType));
            assertThat(strings(schema.get("required")))
                    .as(schemaName + " required")
                    .containsExactlyInAnyOrderElementsOf(REQUEST_REQUIRED.get(schemaName));
        });
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

                if (type.isRecord()) {
                    assertThat(property.get("$ref")).as(field)
                            .isEqualTo(SCHEMA_PREFIX + schemaOf(SCHEMA_RECORDS, type));
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
                    assertThat(map(property.get("items")).get("$ref"))
                            .as(field + " items")
                            .isEqualTo(SCHEMA_PREFIX + schemaOf(SCHEMA_RECORDS, element));
                }
            }
        });
    }

    // 契约写的长度、格式和数量上限就是服务校验的那些。
    @Test
    void limitsInTheContractAreTheOnesTheServiceEnforces() {
        Map<String, Object> schemas = schemas();
        for (String schemaName : List.of("Address", "AddressForm")) {
            Map<String, Object> properties = map(map(schemas.get(schemaName)).get("properties"));

            assertThat(map(properties.get("recipientName")).get("maxLength"))
                    .as(schemaName).isEqualTo(AccountAddressService.MAX_NAME_LENGTH);
            assertThat(map(properties.get("detail")).get("maxLength"))
                    .as(schemaName).isEqualTo(AccountAddressService.MAX_DETAIL_LENGTH);
            assertThat(map(properties.get("phone")).get("pattern")).as(schemaName).isEqualTo("^1[3-9][0-9]{9}$");
        }
        assertThat(map(map(map(schemas.get("AddressList")).get("properties")).get("items")).get("maxItems"))
                .isEqualTo(AccountAddressService.MAX_ADDRESSES);
    }

    // 地址的业务拒绝：全局异常处理给出的状态码和错误码，契约里在同一个状态码下写了它；不是自己的地址是通用的 404。
    @Test
    void theAddressErrorCodesAreDeclaredUnderTheStatusTheHandlerAnswers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<ApiError> limit = handler.handleAddressLimit(
                new AddressLimitException(), new MockHttpServletRequest());
        ResponseEntity<ApiError> notFound = handler.handleReservationNotFound(
                new AddressNotFoundException(), new MockHttpServletRequest());

        assertThat(limit.getStatusCode().value()).isEqualTo(409);
        assertThat(limit.getBody().code()).isEqualTo("ACCOUNT_ADDRESS_LIMIT");
        assertThat(notFound.getStatusCode().value()).isEqualTo(404);
        assertThat(notFound.getBody().code()).isEqualTo("COMMON_NOT_FOUND");
        assertThat((String) map(map(declared("post", "/api/v1/account/addresses").get("responses")).get("409"))
                .get("description"))
                .contains("ACCOUNT_ADDRESS_LIMIT");

        Set<String> produced = Set.of(limit.getBody().code());
        assertThat(strings(map(schemas().get("AccountErrorCode")).get("enum")))
                .containsExactlyInAnyOrderElementsOf(produced);
        // 反过来，契约里提到的 ACCOUNT_ 错误码都是服务端真的会给出的。
        Set<String> mentioned = new TreeSet<>();
        Matcher matcher = Pattern.compile("ACCOUNT_[A-Z_]+").matcher(contractText());
        while (matcher.find()) {
            mentioned.add(matcher.group());
        }
        assertThat(mentioned).containsExactlyInAnyOrderElementsOf(produced);
    }

    @Test
    void everySchemaInTheContractIsCoveredByThisTest() {
        List<String> covered = new ArrayList<>(SCHEMA_RECORDS.keySet());
        covered.addAll(REQUEST_RECORDS.keySet());
        covered.add("AccountErrorCode");
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
        for (Method method : AccountAddressController.class.getDeclaredMethods()) {
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

    private static List<String> fields(Class<? extends Record> type) {
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

    private static Map<String, Object> root() {
        return new Yaml().load(contractText());
    }

    private static Map<String, Object> schemas() {
        return map(map(root().get("components")).get("schemas"));
    }

    private static Map<String, Object> declared(Operation operation) {
        return declared(operation.httpMethod().toLowerCase(), operation.path());
    }

    private static Map<String, Object> declared(String httpMethod, String path) {
        Map<String, Object> item = map(map(root().get("paths")).get(path));
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
        return map(map(map(root().get("components")).get("parameters")).get(name));
    }

    private static String jsonSchemaRef(Map<String, Object> bodyOrResponse) {
        Map<String, Object> content = map(bodyOrResponse.get("content"));
        return (String) map(map(content.get("application/json")).get("schema")).get("$ref");
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
