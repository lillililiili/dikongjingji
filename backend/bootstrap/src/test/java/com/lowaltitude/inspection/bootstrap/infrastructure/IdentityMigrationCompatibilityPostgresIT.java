package com.lowaltitude.inspection.bootstrap.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class IdentityMigrationCompatibilityPostgresIT {
    private final String schema = "identitycompat_" + UUID.randomUUID().toString().replace("-", "");
    private final ModuleFlywayFactory factory = new ModuleFlywayFactory();
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private DataSource source;
    @TempDir Path scripts;

    @BeforeEach void createOwnSchema() {
        String database = System.getenv("E2E_PG_DATABASE");
        assertThat(database).matches("inspection_e2e(?:_[a-z0-9_]+)?");
        String url = "jdbc:postgresql://127.0.0.1:15438/" + database;
        String password = System.getenv("E2E_DATABASE_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, "inspection_e2e", password));
        admin.execute("CREATE SCHEMA " + schema);
        source = new DriverManagerDataSource(url + "?currentSchema=" + schema, "inspection_e2e", password);
        db = new JdbcTemplate(source);
    }

    @AfterEach void closeOwnSchema() {
        if (admin != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    private Flyway startup() {
        return new ModuleFlywayConfiguration().identityFlyway(factory, source);
    }

    private List<String> history(int through) {
        return db.queryForList("""
                SELECT row_to_json(h)::text FROM flyway_history_identity h
                WHERE version::int <= ? ORDER BY installed_rank
                """, String.class, through);
    }

    private void installOldRelease(int through, boolean unknownChecksum) throws Exception {
        for (var resource : new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/identity/V*.sql")) {
            String canonical = resource.getFilename();
            String filename = canonical;
            if (canonical.startsWith("V013__")) continue;
            if (canonical.startsWith("V014__")) filename = canonical.replace("V014__", "V013__");
            if (canonical.startsWith("V015__")) filename = canonical.replace("V015__", "V014__");
            if (canonical.startsWith("V016__")) filename = canonical.replace("V016__", "V015__");
            try (var input = resource.getInputStream()) {
                Files.copy(input, scripts.resolve(filename));
            }
            if (unknownChecksum && filename.startsWith("V013__")) {
                Files.writeString(scripts.resolve(filename), "\n-- hypothetical unrecognized release\n", StandardOpenOption.APPEND);
            }
        }
        Flyway.configure().dataSource(source).table("flyway_history_identity").target(Integer.toString(through))
                .locations("filesystem:" + scripts.toAbsolutePath()).load().migrate();
    }

    @ParameterizedTest @ValueSource(ints = {13, 14, 15})
    void upgradesAReleasedPrefixWithoutChangingItsHistoryOrApplicantInformation(int through) throws Exception {
        installOldRelease(through, false);
        var before = history(through);
        UUID reviewer = db.queryForObject("""
                INSERT INTO identity_user(login_name,display_name,password_hash)
                VALUES ('tester','审核人','!') RETURNING id
                """, UUID.class);
        UUID shop = db.queryForObject("""
                INSERT INTO identity_organization(organization_code,organization_name,organization_type,signup_request_signature)
                VALUES ('pending-shop','迁移前的申请','PROVIDER', repeat('a',64)) RETURNING id
                """, UUID.class);
        db.update("""
                INSERT INTO identity_organization_qualification
                (organization_id,subject_name,license_number,status,submitted_at,submitted_by)
                VALUES (?, '原申请主体','911101000000000001','SUBMITTED',now(),?)
                """, shop, reviewer);
        db.update("""
                INSERT INTO identity_organization(organization_code,organization_name,organization_type)
                VALUES ('historical-shop','既有经营机构','PROVIDER')
                """);

        assertThat(startup().migrate().migrationsExecuted).isEqualTo(16 - through);
        assertThat(history(through)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT listing_suspended FROM identity_organization WHERE id=?", Boolean.class, shop)).isTrue();
        assertThat(db.queryForObject("SELECT signup_request_signature FROM identity_organization WHERE id=?", String.class, shop))
                .isEqualTo("a".repeat(64));
        assertThat(db.queryForObject("SELECT listing_suspended FROM identity_organization WHERE organization_code='historical-shop'",
                Boolean.class)).isFalse();
        assertThat(db.queryForObject("SELECT script FROM flyway_history_identity WHERE version='016'", String.class))
                .isEqualTo("V016__identity_listing_suspended.sql");
        var after = history(16);
        assertThat(startup().migrate().migrationsExecuted).isZero();
        assertThat(history(16)).isEqualTo(after);
    }

    @Test void emptyAndCanonicalDatabasesUseMainlineIdentityVersions() {
        assertThat(startup().migrate().migrationsExecuted).isGreaterThanOrEqualTo(16);
        assertThat(db.queryForObject("SELECT script FROM flyway_history_identity WHERE version='013'", String.class))
                .isEqualTo("V013__identity_listing_suspended.sql");
        var before = history(16);
        factory.identity(source).validate();
        assertThat(startup().migrate().migrationsExecuted).isZero();
        assertThat(history(16)).isEqualTo(before);
    }

    @Test void unknownChecksumCannotSelectTheLegacyHistory() throws Exception {
        installOldRelease(13, true);
        var before = history(13);
        assertThatThrownBy(() -> startup().migrate()).hasMessageContaining("Unrecognized");
        assertThat(history(16)).isEqualTo(before);
    }

    @Test void refusesKnownHistoryIfItsRecordedEffectsAreMissing() throws Exception {
        installOldRelease(14, false);
        db.execute("ALTER TABLE identity_login_session RENAME COLUMN password_setup_until TO unexpected_column");
        var before = history(14);
        assertThatThrownBy(() -> startup().migrate()).hasMessageContaining("password_setup_until");
        assertThat(history(16)).isEqualTo(before);
    }

    @Test void laterSharedMigrationCanEvolveTheSchemaAndRestartOnTheLegacyLineage() throws Exception {
        installOldRelease(15, false);
        assertThat(startup().migrate().migrationsExecuted).isOne();
        Path extraClasspath = scripts.resolve("shared-release");
        Path identity = Files.createDirectories(extraClasspath.resolve("db/migration/identity"));
        Files.writeString(identity.resolve("V017__test_evolve_session.sql"),
                "ALTER TABLE identity_login_session RENAME COLUMN password_setup_until TO setup_until_v17;");
        var thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try (var future = new URLClassLoader(new URL[] {extraClasspath.toUri().toURL()}, original)) {
            thread.setContextClassLoader(future);
            assertThat(startup().migrate().migrationsExecuted).isOne();
            assertThat(db.queryForObject("SELECT script FROM flyway_history_identity WHERE version='017'", String.class))
                    .isEqualTo("V017__test_evolve_session.sql");
            var before = history(17);
            assertThat(startup().migrate().migrationsExecuted).isZero();
            assertThat(history(17)).isEqualTo(before);
        } finally {
            thread.setContextClassLoader(original);
        }
    }
}
