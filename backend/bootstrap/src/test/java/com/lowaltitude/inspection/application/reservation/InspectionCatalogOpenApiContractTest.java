package com.lowaltitude.inspection.application.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 目录契约测试：路由声明、T02 新增字段，以及示例文件能通过契约 schema 的结构校验。
 * 校验器只覆盖本契约用到的 JSON Schema 子集（type/null、required、
 * additionalProperties:false、enum、items、$ref、anyOf），不引入新依赖。
 */
class InspectionCatalogOpenApiContractTest {

    private static final Path CONTRACT = Path.of(
            "..", "..", "contracts", "openapi-inspection-catalog.yaml");
    private static final Path EXAMPLES = Path.of(
            "..", "..", "contracts", "inspection-catalog.examples.json");

    @Test
    void everyCatalogRouteIsDeclaredInTheContract() throws Exception {
        String contract = Files.readString(CONTRACT);

        List<String> routes = new ArrayList<>();
        for (Method method : InspectionServiceCatalogController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get != null) {
                routes.add(get.value()[0]);
            }
        }

        assertThat(routes).hasSizeGreaterThanOrEqualTo(2);
        assertThat(routes).allSatisfy(route ->
                assertThat(contract).contains("  " + route + ":\n"));
    }

    @Test
    void contractDeclaresPricingAndCancellationFieldsAsNullable() throws Exception {
        Map<String, Object> schemas = schemas();

        Map<String, Object> card = properties(schemas, "InspectionServiceCard");
        assertThat(card).containsKey("priceNote");
        assertThat(types(card.get("priceNote"))).containsExactlyInAnyOrder("string", "null");
        assertThat(required(schemas, "InspectionServiceCard")).doesNotContain("priceNote");

        Map<String, Object> detail = properties(schemas, "InspectionServiceDetail");
        assertThat(detail).containsKeys("priceNote", "priceFactors", "cancellationNote", "serviceCities");
        assertThat(types(detail.get("priceNote"))).containsExactlyInAnyOrder("string", "null");
        assertThat(types(detail.get("priceFactors"))).containsExactlyInAnyOrder("array", "null");
        assertThat(types(detail.get("cancellationNote"))).containsExactlyInAnyOrder("string", "null");
        assertThat(types(detail.get("serviceCities"))).containsExactlyInAnyOrder("array", "null");
        assertThat(itemsRef(detail.get("serviceCities"))).isEqualTo("#/components/schemas/CitySummary");
        assertThat(required(schemas, "InspectionServiceDetail"))
                .doesNotContain("priceNote", "priceFactors", "cancellationNote", "serviceCities");
    }

    @Test
    void examplesConformToContractSchemas() throws Exception {
        Map<String, Object> schemas = schemas();
        JsonNode examples = new ObjectMapper().readTree(EXAMPLES.toFile());

        validate(schemas, "InspectionPortal", examples.get("portal"), "portal");
        validate(schemas, "InspectionFilterOptions", examples.get("filters"), "filters");

        JsonNode pages = examples.get("servicePageScenarios");
        pages.fieldNames().forEachRemaining(name ->
                validate(schemas, "InspectionServicePage", pages.get(name), "servicePageScenarios." + name));

        JsonNode details = examples.get("serviceDetailScenarios");
        assertThat(details).isNotNull();
        details.fieldNames().forEachRemaining(name -> {
            if (!name.startsWith("_")) {
                validate(schemas, "InspectionServiceDetail", details.get(name), "serviceDetailScenarios." + name);
            }
        });

        JsonNode power = details.get("powerWithPricingAndCancellation");
        assertThat(power.get("priceFactors")).hasSize(3);
        assertThat(power.get("cancellationNote").asText()).contains("方案确认前可取消");
        assertThat(power.get("serviceCities").get(0).get("code").asText()).isEqualTo("370500");

        JsonNode legacy = details.get("legacyProjectQuoteWithoutNewFields");
        assertThat(legacy.get("priceFactors").isNull()).isTrue();
        assertThat(legacy.get("cancellationNote").isNull()).isTrue();
        assertThat(legacy.get("serviceCities").isNull()).isTrue();
    }

    // ---- 轻量 schema 校验 ----

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemas() throws Exception {
        Map<String, Object> root = new Yaml().load(Files.readString(CONTRACT));
        Map<String, Object> components = (Map<String, Object>) root.get("components");
        return (Map<String, Object>) components.get("schemas");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Map<String, Object> schemas, String name) {
        Map<String, Object> schema = (Map<String, Object>) schemas.get(name);
        return (Map<String, Object>) schema.get("properties");
    }

    @SuppressWarnings("unchecked")
    private static List<String> required(Map<String, Object> schemas, String name) {
        Map<String, Object> schema = (Map<String, Object>) schemas.get(name);
        Object required = schema.get("required");
        return required == null ? List.of() : (List<String>) required;
    }

    @SuppressWarnings("unchecked")
    private static List<String> types(Object schema) {
        Object type = ((Map<String, Object>) schema).get("type");
        return type instanceof List<?> list ? (List<String>) list : List.of((String) type);
    }

    @SuppressWarnings("unchecked")
    private static String itemsRef(Object schema) {
        Map<String, Object> items = (Map<String, Object>) ((Map<String, Object>) schema).get("items");
        return (String) items.get("$ref");
    }

    private static void validate(Map<String, Object> schemas, String schemaName, JsonNode node, String path) {
        validate(schemas, resolve(schemas, "#/components/schemas/" + schemaName), node, path);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolve(Map<String, Object> schemas, String ref) {
        String name = ref.substring(ref.lastIndexOf('/') + 1);
        Map<String, Object> schema = (Map<String, Object>) schemas.get(name);
        assertThat(schema).as("schema %s", ref).isNotNull();
        return schema;
    }

    @SuppressWarnings("unchecked")
    private static void validate(Map<String, Object> schemas, Map<String, Object> schema, JsonNode node, String path) {
        assertThat(node).as("%s is missing", path).isNotNull();

        if (schema.containsKey("$ref")) {
            validate(schemas, resolve(schemas, (String) schema.get("$ref")), node, path);
            return;
        }
        if (schema.containsKey("anyOf")) {
            List<AssertionError> failures = new ArrayList<>();
            for (Object option : (List<Object>) schema.get("anyOf")) {
                try {
                    validate(schemas, (Map<String, Object>) option, node, path);
                    return;
                } catch (AssertionError error) {
                    failures.add(error);
                }
            }
            throw new AssertionError(path + " matches no anyOf option: " + failures);
        }

        List<String> types = types(schema);
        if (node.isNull()) {
            assertThat(types).as("%s must be nullable", path).contains("null");
            return;
        }
        if (schema.containsKey("enum")) {
            assertThat((List<Object>) schema.get("enum")).as("%s enum", path).contains(node.asText());
        }
        if (node.isObject()) {
            assertThat(types).as("%s type", path).contains("object");
            Map<String, Object> properties = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
            for (String key : required(schema)) {
                assertThat(node.has(key)).as("%s.%s is required", path, key).isTrue();
            }
            node.fieldNames().forEachRemaining(key -> {
                if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                    assertThat(properties).as("%s.%s is not declared", path, key).containsKey(key);
                }
                if (properties.containsKey(key)) {
                    validate(schemas, (Map<String, Object>) properties.get(key), node.get(key), path + "." + key);
                }
            });
        } else if (node.isArray()) {
            assertThat(types).as("%s type", path).contains("array");
            Map<String, Object> items = (Map<String, Object>) schema.get("items");
            for (int i = 0; i < node.size(); i++) {
                validate(schemas, items, node.get(i), path + "[" + i + "]");
            }
        } else if (node.isTextual()) {
            assertThat(types).as("%s type", path).contains("string");
        } else if (node.isBoolean()) {
            assertThat(types).as("%s type", path).contains("boolean");
        } else if (node.isIntegralNumber()) {
            assertThat(types).as("%s type", path).containsAnyOf("integer", "number");
        } else if (node.isNumber()) {
            assertThat(types).as("%s type", path).contains("number");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> required(Map<String, Object> schema) {
        Object required = schema.get("required");
        return required == null ? List.of() : (List<String>) required;
    }
}
