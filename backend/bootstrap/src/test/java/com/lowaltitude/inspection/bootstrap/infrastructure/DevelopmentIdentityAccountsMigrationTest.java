package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;

class DevelopmentIdentityAccountsMigrationTest {

    private static final String SEED = "/db/development/identity/R__dev_identity_accounts.sql";

    @Test
    void loadsSampleAccountsOnlyInDevFromAnIsolatedFlywayHistory() throws Exception {
        Method migrationMethod = Arrays.stream(ModuleFlywayConfiguration.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("identityDevelopmentDataFlyway"))
                .findFirst()
                .orElseThrow();

        assertThat(migrationMethod.getAnnotation(Profile.class).value()).containsExactly("dev");
        assertThat(migrationMethod.getAnnotation(DependsOn.class).value()).containsExactly("identityFlyway");

        migrationMethod.setAccessible(true);
        Flyway flyway = (Flyway) migrationMethod.invoke(
                new ModuleFlywayConfiguration(),
                mock(DataSource.class));

        assertThat(flyway.getConfiguration().getTable())
                .isEqualTo("flyway_history_identity_development");
        assertThat(Arrays.stream(flyway.getConfiguration().getLocations()).map(Object::toString))
                .containsExactly("classpath:db/development/identity");
    }

    // 密码公开写在仓库里，所以这份种子只能给出客户和服务商操作员，不能给出平台运营权限。
    @Test
    void seedCreatesACustomerAndAProviderOperatorAndNoPlatformOperator() throws Exception {
        String sql = seed();

        assertThat(sql)
                .contains("'dev-customer'")
                .contains("'dev-provider'")
                .contains("role_code = 'CUSTOMER'")
                .contains("role_code = 'PROVIDER_OPERATOR'")
                .doesNotContain("PLATFORM_OPERATOR")
                .doesNotContain("'PLATFORM'");
        assertThat(sql.split("INSERT INTO identity_user \\(", -1)).hasSize(2);
        assertThat(sql).doesNotContain("DELETE FROM").doesNotContain("TRUNCATE");
    }

    // 生产迁移目录里不能出现任何账号或口令。
    @Test
    void noVersionedMigrationCreatesAnAccount() throws IOException {
        Path modules = Path.of("..", "modules");
        try (Stream<Path> files = Files.walk(modules)) {
            assertThat(files
                    .filter(file -> file.toString().endsWith(".sql"))
                    .filter(file -> file.toString().contains("migrations"))
                    .filter(file -> {
                        try {
                            String sql = Files.readString(file, StandardCharsets.UTF_8);
                            return sql.contains("INSERT INTO identity_user ") || sql.contains("crypt(");
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    }))
                    .isEmpty();
        }
    }

    private String seed() throws IOException {
        try (var stream = getClass().getResourceAsStream(SEED)) {
            assertThat(stream).isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
