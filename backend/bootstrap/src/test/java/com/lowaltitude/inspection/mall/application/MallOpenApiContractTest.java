package com.lowaltitude.inspection.mall.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.yaml.snakeyaml.Yaml;

/** 契约和实现逐项对照：路由、每个返回对象的字段、每个枚举的取值。 */
class MallOpenApiContractTest {

    private static final Path CONTRACT = Path.of("..", "..", "contracts", "openapi-mall-catalog.yaml");

    private static final Map<String, Class<? extends Record>> SCHEMA_RECORDS = Map.ofEntries(
            Map.entry("MediaAsset", MallMediaView.class),
            Map.entry("MallListPrice", MallListPriceView.class),
            Map.entry("MallProductCard", MallProductCardView.class),
            Map.entry("MallProductPage", MallProductPageView.class),
            Map.entry("MallMerchant", MallMerchantView.class),
            Map.entry("MallOffer", MallOfferView.class),
            Map.entry("MallSpec", MallSpecView.class),
            Map.entry("MallPackItem", MallPackItemView.class),
            Map.entry("MallProductDetail", MallProductDetailView.class));

    private static final Map<String, Class<? extends Enum<?>>> SCHEMA_ENUMS = Map.of(
            "MallCategory", MallCategory.class,
            "MallProductSort", MallProductSort.class,
            "MallSaleMode", MallSaleMode.class,
            "MallExperienceBadge", MallExperienceBadge.class,
            "MallPriceUnit", MallPriceUnit.class,
            "MallMerchantKind", MallMerchantKind.class);

    @Test
    void everyRouteIsDeclaredAsAPublicOperation() throws Exception {
        List<String> routes = new ArrayList<>();
        for (Method method : MallCatalogController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get != null) {
                routes.add(get.value()[0]);
            }
        }
        Map<String, Object> paths = map(root().get("paths"));

        assertThat(routes).hasSize(2);
        assertThat(paths.keySet()).containsExactlyInAnyOrderElementsOf(routes);
        assertThat(paths.values()).allSatisfy(path ->
                assertThat(map(map(path).get("get")).get("security")).isEqualTo(List.of()));
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
    void enumValuesMatchTheContract() {
        Map<String, Object> schemas = schemas();

        assertThat(SCHEMA_ENUMS).allSatisfy((schemaName, enumType) ->
                assertThat(strings(map(schemas.get(schemaName)).get("enum")))
                        .as(schemaName)
                        .containsExactlyElementsOf(Arrays.stream(enumType.getEnumConstants())
                                .map(Enum::name)
                                .toList()));
    }

    // 1.2.0：商品详情的每个供给说明这家商家能不能在线收款，总是给出（不会是 null）。
    @Test
    void eachOfferAlwaysSaysWhetherItsMerchantTakesPaymentOnline() {
        Map<String, Object> offer = map(schemas().get("MallOffer"));

        assertThat(map(root().get("info")).get("version")).isEqualTo("1.2.0");
        assertThat(map(map(offer.get("properties")).get("onlinePayment")).get("type")).isEqualTo("boolean");
        assertThat(strings(offer.get("required"))).contains("onlinePayment");
        assertThat(Arrays.stream(MallOfferView.class.getRecordComponents())
                .filter(component -> component.getName().equals("onlinePayment"))
                .map(RecordComponent::getType)).containsExactly(boolean.class);
    }

    @Test
    void everySchemaInTheContractIsCoveredByThisTest() {
        List<String> covered = new ArrayList<>(SCHEMA_RECORDS.keySet());
        covered.addAll(SCHEMA_ENUMS.keySet());
        covered.add("ApiError");

        assertThat(schemas().keySet()).containsExactlyInAnyOrderElementsOf(covered);
    }

    private static Map<String, Object> root() {
        try {
            return new Yaml().load(Files.readString(CONTRACT));
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static Map<String, Object> schemas() {
        return map(map(root().get("components")).get("schemas"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return (List<String>) value;
    }
}
