package com.lowaltitude.inspection.experience.application;

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
class ExperienceOpenApiContractTest {

    private static final Path CONTRACT = Path.of("..", "..", "contracts", "openapi-experience.yaml");

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
            Map.entry("VenueSession", VenueSessionView.class),
            Map.entry("VenueSessionList", VenueSessionListView.class),
            Map.entry("VenueDevice", VenueDeviceView.class),
            Map.entry("VenueDevicePage", VenueDevicePageView.class));

    private static final Map<String, Class<? extends Enum<?>>> SCHEMA_ENUMS = Map.of(
            "ExperienceType", ExperienceType.class,
            "VenueSessionStatus", VenueSessionStatus.class,
            "VenueDeviceCategory", VenueDeviceCategory.class,
            "VenueDeviceTrialMode", VenueDeviceTrialMode.class);

    @Test
    void everyRouteIsDeclaredAsAPublicOperation() throws Exception {
        List<String> routes = new ArrayList<>();
        for (Method method : ExperienceCatalogController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            if (get != null) {
                routes.add(get.value()[0]);
            }
        }
        Map<String, Object> paths = map(root().get("paths"));

        assertThat(routes).hasSize(5);
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
