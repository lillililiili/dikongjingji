package com.lowaltitude.inspection.proservice.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * 契约 contracts/openapi-proservice.yaml 和实现逐项对照（F2）：三个公开路由、查询参数、返回对象的字段和类型、
 * 枚举和中文名、长度和页大小上限。改了一边忘了另一边会立刻失败。
 */
class ProServiceOpenApiContractTest {

    private static final Path CONTRACT = Path.of("..", "..", "contracts", "openapi-proservice.yaml");
    private static final String SCHEMA_PREFIX = "#/components/schemas/";

    private static final Map<String, Class<? extends Record>> SCHEMA_RECORDS = Map.ofEntries(
            Map.entry("ProServiceCategoryView", ProServiceCategoryView.class),
            Map.entry("ProServicePrice", ProServicePriceView.class),
            Map.entry("ProServiceFacetView", ProServiceFacetView.class),
            Map.entry("ProServiceFacetGroup", ProServiceFacetGroupView.class),
            Map.entry("ProServiceCard", ProServiceCardView.class),
            Map.entry("ProServicePage", ProServicePageView.class),
            Map.entry("ProServiceLine", ProServiceLineView.class),
            Map.entry("ProServiceCity", ProServiceCityView.class),
            Map.entry("ProServiceProvider", ProServiceProviderView.class),
            Map.entry("ProServiceDetail", ProServiceDetailView.class),
            Map.entry("ProviderInspectionService", ProviderInspectionServiceView.class),
            Map.entry("ProviderPage", ProviderPageView.class),
            Map.entry("ProviderProfile", ProviderProfileService.ProfileView.class),
            Map.entry("ProviderProfileReview", ProviderProfileService.ReviewView.class));

    private static final Map<String, Class<? extends Enum<?>>> SCHEMA_ENUMS = Map.of(
            "ProServiceType", ProServiceType.class,
            "ProServiceFacet", ProServiceFacet.class,
            "ProServiceCategory", ProServiceCategory.class,
            "ProServicePriceMode", ProServicePriceMode.class,
            "ProServiceSort", ProServiceSort.class,
            "ProviderProfileReviewStatus", ProviderProfileService.ReviewStatus.class);

    @Test
    void contractIsVersionOneAndEveryRouteIsPublic() {
        Map<String, Object> root = root();

        assertThat(map(root.get("info")).get("version")).isEqualTo("1.2.0");
        assertThat(root).doesNotContainKey("security");
        assertThat(operations()).extracting(Operation::key).containsExactlyInAnyOrder(
                "GET /api/v1/pro-services",
                "GET /api/v1/pro-services/{serviceId}",
                "GET /api/v1/providers/{providerId}");
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
        // 1.2.0：工作台的服务商资料两个路由要登录，其余公开。
        assertThat(declaredKeys).hasSize(5).contains("GET /api/v1/provider/profile", "PUT /api/v1/provider/profile");
    }

    @Test
    void theWorkbenchProfileRoutesNeedALoginAndMatchTheController() {
        Map<String, Object> path = map(map(root().get("paths")).get("/api/v1/provider/profile"));
        for (String method : List.of("get", "put")) {
            Map<String, Object> declared = map(path.get(method));
            assertThat(declared.get("security")).as(method).isEqualTo(List.of(Map.of("bearerAuth", List.of())));
            assertThat(map(declared.get("responses")).keySet()).as(method).contains("200", "401", "403");
            assertThat((String) map(map(map(map(map(declared.get("responses")).get("200")).get("content"))
                    .get("application/json")).get("schema")).get("$ref"))
                    .isEqualTo(SCHEMA_PREFIX + "ProviderProfileReview");
        }
        Map<String, Object> request = map(schemas().get("ProviderProfileRequest"));
        assertThat(map(request.get("properties")).keySet()).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(ProviderProfileController.ProfileRequest.class.getRecordComponents())
                        .map(RecordComponent::getName).toList());
        assertThat(strings(request.get("required")))
                .containsExactlyInAnyOrder("legalEntity", "serviceModes", "deliverables", "version");
        Map<String, Object> properties = map(request.get("properties"));
        assertThat(map(properties.get("legalEntity")).get("maxLength")).isEqualTo(ProviderProfileService.MAX_LEGAL_ENTITY);
        assertThat(map(properties.get("serviceModes")).get("maxLength"))
                .isEqualTo(ProviderProfileService.MAX_SERVICE_MODES);
        assertThat(map(properties.get("deliverables")).get("maxItems")).isEqualTo(ProviderProfileService.MAX_DELIVERABLES);
        assertThat(map(map(properties.get("deliverables")).get("items")).get("maxLength"))
                .isEqualTo(ProviderProfileService.MAX_DELIVERABLE);
        assertThat(map(properties.get("localTeamLabel")).get("maxLength")).isEqualTo(ProviderProfileService.MAX_LOCAL_TEAM);
        assertThat(contractText()).contains("PRO_PROVIDER_PROFILE_VERSION_CONFLICT");
        assertThat(ProviderProfileService.ProfileError.SUBMIT_VERSION_CONFLICT.code())
                .isEqualTo("PRO_PROVIDER_PROFILE_VERSION_CONFLICT");
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
        Map<String, Object> list = declared(new Operation("GET", "/api/v1/pro-services", null));
        Map<String, Map<String, Object>> parameters = new LinkedHashMap<>();
        for (Object entry : list(list.get("parameters"))) {
            Map<String, Object> parameter = resolve(map(entry));
            parameters.put((String) parameter.get("name"), parameter);
        }
        assertThat(map(parameters.get("type").get("schema")).get("$ref")).isEqualTo(SCHEMA_PREFIX + "ProServiceType");
        assertThat(map(map(parameters.get("deliverables").get("schema")).get("items")).get("$ref"))
                .isEqualTo(SCHEMA_PREFIX + "ProServiceFacet");
        assertThat(map(parameters.get("priceMode").get("schema")).get("$ref"))
                .isEqualTo(SCHEMA_PREFIX + "ProServicePriceMode");
        assertThat(map(parameters.get("sort").get("schema")).get("$ref")).isEqualTo(SCHEMA_PREFIX + "ProServiceSort");
        assertThat(map(parameters.get("size").get("schema")).get("maximum")).isEqualTo(ProServiceReadService.MAX_PAGE_SIZE);
        assertThat(map(parameters.get("keyword").get("schema")).get("maxLength"))
                .isEqualTo(ProServiceReadService.MAX_KEYWORD_LENGTH);
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
                if (javaType == List.class) {
                    Class<?> element = (Class<?>) ((ParameterizedType) component.getGenericType())
                            .getActualTypeArguments()[0];
                    Map<String, Object> items = map(property.get("items"));
                    if (element.isRecord()) {
                        assertThat(items.get("$ref")).as(field)
                                .isEqualTo(SCHEMA_PREFIX + schemaOf(SCHEMA_RECORDS, element));
                    } else {
                        assertThat(element).as(field).isEqualTo(String.class);
                        assertThat(items).as(field).containsEntry("type", "string");
                    }
                }
            }
        });
        // 只有可能为空的字段声明成可空。
        Map<String, Object> page = map(map(schemas.get("ProServicePage")).get("properties"));
        assertThat(types(map(page.get("cityName")))).contains("null");
        assertThat(types(map(page.get("cityCode")))).doesNotContain("null");
        Map<String, Object> detail = map(map(schemas.get("ProServiceDetail")).get("properties"));
        assertThat(list(map(detail.get("provider")).get("anyOf"))).contains(Map.of("type", "null"));
    }

    // 枚举的取值和顺序、类型和筛选组、中文名：契约里写的就是 Java 给出的。
    @Test
    void enumsLabelsAndFacetGroupsMatchTheJavaSide() {
        Map<String, Object> schemas = schemas();

        assertThat(SCHEMA_ENUMS).allSatisfy((name, type) -> assertThat(strings(map(schemas.get(name)).get("enum")))
                .as(name)
                .containsExactlyElementsOf(Arrays.stream(type.getEnumConstants()).map(Enum::name).toList()));
        String types = (String) map(schemas.get("ProServiceType")).get("description");
        String facets = (String) map(schemas.get("ProServiceFacet")).get("description");
        String categories = (String) map(schemas.get("ProServiceCategory")).get("description");
        for (ProServiceType type : ProServiceType.values()) {
            assertThat(types).contains(type.name() + " " + type.label());
            assertThat(facets).contains(type.name() + " " + type.facetLabel() + " "
                    + String.join("、", type.facets().stream().map(Enum::name).toList()));
        }
        for (ProServiceFacet facet : ProServiceFacet.values()) {
            assertThat(facets).contains(facet.name() + " " + facet.label());
        }
        for (ProServiceCategory category : ProServiceCategory.values()) {
            assertThat(categories).contains(category.name() + " " + category.label());
        }
        assertThat(contractText()).contains(ProServiceReadService.PROJECT_PRICE_NOTE);
    }

    @Test
    void everySchemaInTheContractIsCoveredByThisTest() {
        List<String> covered = new ArrayList<>(SCHEMA_RECORDS.keySet());
        covered.addAll(SCHEMA_ENUMS.keySet());
        covered.add("ApiError");
        covered.add("ProviderProfileRequest");

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
        for (Method method : ProServiceController.class.getDeclaredMethods()) {
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
        if (type == String.class || type == UUID.class || type == java.time.LocalDate.class
                || type == java.time.OffsetDateTime.class) {
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
