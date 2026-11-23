package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import javax.sql.DataSource;

import com.lowaltitude.inspection.identity.application.OnboardingPorts;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;

/** 运营端（S3-1）的开发数据和本机附件存储。 */
class OpsDevelopmentSeedTest {

    // dev-operator 的口令不写进仓库：只来自环境变量，太短或没设时种子什么都不写。
    @Test
    void theDevOperatorOnlyExistsWhenAPasswordIsGivenAtStartup() throws Exception {
        Method method = Arrays.stream(ModuleFlywayConfiguration.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("identityOperatorDevelopmentDataFlyway"))
                .findFirst().orElseThrow();
        assertThat(method.getAnnotation(Profile.class).value()).containsExactly("dev");
        assertThat(method.getAnnotation(DependsOn.class).value()).containsExactly("identityDevelopmentDataFlyway");
        method.setAccessible(true);
        Flyway flyway = (Flyway) method.invoke(new ModuleFlywayConfiguration(), mock(DataSource.class),
                "a-long-enough-pass");
        assertThat(flyway.getConfiguration().getTable()).isEqualTo("flyway_history_identity_operator_development");
        assertThat(Arrays.stream(flyway.getConfiguration().getLocations()).map(Object::toString))
                .containsExactly("classpath:db/development/identity-operator");

        assertThat(ModuleFlywayConfiguration.devOperatorPlaceholder(null)).containsEntry("devOperatorPassword", "");
        assertThat(ModuleFlywayConfiguration.devOperatorPlaceholder("short")).containsEntry("devOperatorPassword", "");
        assertThat(ModuleFlywayConfiguration.devOperatorPlaceholder("it's-long-enough"))
                .containsEntry("devOperatorPassword", "it''s-long-enough");

        String seed = resource("/db/development/identity-operator/R__dev_identity_operator.sql");
        assertThat(seed).contains("'dev-operator'").contains("crypt('${devOperatorPassword}', gen_salt('bf', 10))")
                .contains("role_code = 'PLATFORM_OPERATOR'").doesNotContain("PLATFORM_SUPPORT'")
                .doesNotContain("test-password");
        assertThat(seed.split("WHERE length\\('\\$\\{devOperatorPassword}'\\) >= 12", -1)).hasSize(3);
        assertThat(seed.split("AND length\\('\\$\\{devOperatorPassword}'\\) >= 12", -1)).hasSize(2);
        assertThat(seed).doesNotContain("DELETE").doesNotContain("TRUNCATE");
    }

    // 照原型 CitySheet：已开通东营，筹备中济南、青岛、潍坊。
    @Test
    void cityServiceStatusSeedFollowsTheCitySheetBoard() throws Exception {
        String seed = resource("/db/development/inspection/R__dev_platform_city_service_status.sql");

        assertThat(seed).contains("('370500', 'OPEN')").contains("('370100', 'PREPARING')")
                .contains("('370200', 'PREPARING')").contains("('370700', 'PREPARING')")
                .contains("service_status_changed_at = now()")
                .doesNotContain("INSERT INTO").doesNotContain("DELETE");
    }

    @Test
    void localFilesStayInsideTheConfiguredDirectory(@TempDir Path root) throws Exception {
        LocalQualificationFileStore store = new LocalQualificationFileStore(root);
        String key = "organizations/" + UUID.randomUUID() + "/qualification/" + UUID.randomUUID();

        store.put(key, "%PDF-1.7".getBytes(StandardCharsets.UTF_8));

        assertThat(new String(store.read(key), StandardCharsets.UTF_8)).isEqualTo("%PDF-1.7");
        assertThat(Files.exists(root.resolve(key))).isTrue();
        for (String bad : new String[] {"../etc/passwd", "organizations/x/qualification/y", key + "/../../x", null}) {
            assertThatThrownBy(() -> store.put(bad, new byte[] {1})).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> store.read("organizations/" + UUID.randomUUID() + "/qualification/"
                + UUID.randomUUID())).isInstanceOf(OnboardingPorts.FileStoreUnavailableException.class);
        assertThatThrownBy(() -> new LocalQualificationFileStore.Unavailable().put(key, new byte[] {1}))
                .isInstanceOf(OnboardingPorts.FileStoreUnavailableException.class);
    }

    private String resource(String path) throws Exception {
        try (var stream = getClass().getResourceAsStream(path)) {
            assertThat(stream).as(path).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
