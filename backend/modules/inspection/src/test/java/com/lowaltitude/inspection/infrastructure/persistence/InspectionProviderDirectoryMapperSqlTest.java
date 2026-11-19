package com.lowaltitude.inspection.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.application.provider.InspectionProviderDirectory;
import com.lowaltitude.inspection.application.provider.InspectionProviderDirectory.ProviderCard;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

/** 给需求报价（F1）用的服务商名录：只有在用的服务商资料和在用的服务，不读联系方式。 */
class InspectionProviderDirectoryMapperSqlTest {

    private static final UUID PROVIDER = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SERVICE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void onlyActiveProviderProfilesOfActiveProviderOrganizationsAreListed() throws Exception {
        for (String name : List.of("findProvidersIn", "findProviders")) {
            assertThat(sql(name))
                    .doesNotContain("${")
                    .contains("p.status = 'ACTIVE'")
                    .contains("o.status = 'ACTIVE'")
                    .contains("o.organization_type = 'PROVIDER'")
                    .contains("ORDER BY o.organization_name ASC, o.id ASC")
                    .doesNotContain("phone")
                    .doesNotContain("contact");
        }
        assertThat(sql("findProvidersIn"))
                .contains("s.status = 'ACTIVE'")
                .contains("c.status = 'ACTIVE'")
                .contains("c.city_code = #{cityCode}");
        assertThat(sql("findProviders")).startsWith("<script>").contains("p.provider_id IN");
        assertThat(sql("findCitiesServed"))
                .doesNotContain("${")
                .contains("s.provider_id = #{providerId}")
                .contains("s.status = 'ACTIVE'")
                .contains("c.status = 'ACTIVE'")
                .contains("p.status = 'ACTIVE'")
                .contains("o.status = 'ACTIVE'");
    }

    @Test
    void theDirectoryTrimsBlanksAndSkipsEmptyQueries() {
        RecordingMapper mapper = new RecordingMapper();
        InspectionProviderDirectory directory = new InspectionProviderDirectory(mapper);

        assertThat(directory.providersIn(" ")).isEmpty();
        assertThat(directory.find(Arrays.asList(null, null))).isEmpty();
        assertThat(directory.citiesServed(null)).isEmpty();
        assertThat(mapper.calls).isEmpty();

        assertThat(directory.providersIn(" 370500 ")).containsExactly(
                new ProviderCard(PROVIDER, "云翼科技", "企业认证", null));
        assertThat(directory.find(List.of(PROVIDER, PROVIDER))).hasSize(1);
        assertThat(directory.citiesServed(PROVIDER)).containsExactly("370100", "370500");
        assertThat(mapper.calls).containsExactly("in:370500", "find:[" + PROVIDER + "]", "cities:" + PROVIDER);
    }

    // 服务商主页（F2）：这家服务商在这个城市的巡检服务，可见条件和巡检目录是同一份。
    @Test
    void providerServicesUseTheCatalogVisibilityAndReadablePrices() throws Exception {
        assertThat(sql("findServicesOf"))
                .contains(InspectionServiceCatalogMapper.VISIBLE_IN_CITY)
                .contains("service.provider_id = #{providerId}")
                .contains("ORDER BY service.title ASC, service.id ASC")
                .doesNotContain("${");
        RecordingMapper mapper = new RecordingMapper();
        InspectionProviderDirectory directory = new InspectionProviderDirectory(mapper);

        assertThat(directory.servicesOf(PROVIDER, " ")).isEmpty();
        assertThat(directory.servicesOf(null, "370500")).isEmpty();
        assertThat(mapper.calls).isEmpty();
        assertThat(directory.servicesOf(PROVIDER, " 370500 ")).containsExactly(
                new InspectionProviderDirectory.ServiceCard(SERVICE, "电力输电线路无人机巡检", "高精度巡检", "电力", false,
                        69900L, "/公里起", "含现场巡检和报告"),
                new InspectionProviderDirectory.ServiceCard(SERVICE, "热力管网巡检服务", "管网评估", "热力", false,
                        99900L, "/次", null),
                new InspectionProviderDirectory.ServiceCard(SERVICE, "园区安防巡检", "周界巡查", "园区", true, null,
                        null, null));
        assertThat(mapper.calls).containsExactly("services:" + PROVIDER + ":370500");
    }

    private static String sql(String name) throws NoSuchMethodException {
        Method method = Arrays.stream(InspectionProviderDirectoryMapper.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException(name));
        return String.join("\n", method.getAnnotation(Select.class).value());
    }

    private static final class RecordingMapper implements InspectionProviderDirectoryMapper {
        final List<String> calls = new java.util.ArrayList<>();

        @Override
        public List<ProviderRow> findProvidersIn(String cityCode) {
            calls.add("in:" + cityCode);
            return List.of(new ProviderRow(PROVIDER, "云翼科技", " 企业认证 ", " "));
        }

        @Override
        public List<ProviderRow> findProviders(List<UUID> providerIds) {
            calls.add("find:" + providerIds);
            return List.of(new ProviderRow(PROVIDER, "云翼科技", null, null));
        }

        @Override
        public List<ServiceRow> findServicesOf(UUID providerId, String cityCode) {
            calls.add("services:" + providerId + ":" + cityCode);
            return List.of(
                    new ServiceRow(SERVICE, "电力输电线路无人机巡检", "高精度巡检", "电力", "STARTING_PRICE", 69900L, "公里",
                            "含现场巡检和报告"),
                    new ServiceRow(SERVICE, "热力管网巡检服务", "管网评估", "热力", "PER_VISIT", 99900L, "次", " "),
                    new ServiceRow(SERVICE, "园区安防巡检", "周界巡查", "园区", "PROJECT_QUOTE", null, null, null));
        }

        @Override
        public List<String> findCitiesServed(UUID providerId) {
            calls.add("cities:" + providerId);
            return List.of("370100", "370500");
        }
    }
}
