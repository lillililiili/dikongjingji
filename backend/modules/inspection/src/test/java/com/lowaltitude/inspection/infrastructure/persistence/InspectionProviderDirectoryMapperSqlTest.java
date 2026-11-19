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
        public List<String> findCitiesServed(UUID providerId) {
            calls.add("cities:" + providerId);
            return List.of("370100", "370500");
        }
    }
}
